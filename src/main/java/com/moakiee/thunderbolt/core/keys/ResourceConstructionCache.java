package com.moakiee.thunderbolt.core.keys;

import java.util.concurrent.atomic.AtomicReferenceArray;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/** Reuses validated immutable values; cache misses still invoke the original factory. */
public final class ResourceConstructionCache {
    private static final int CAPACITY = 4096;
    private static volatile Tables tables;
    private record Tables(ResourceLocationCanonicalCache locations, AtomicReferenceArray<TagKey<?>> tags) {
        private Tables(boolean locations, boolean tags) {
            this(locations ? new ResourceLocationCanonicalCache() : null,
                    tags ? new AtomicReferenceArray<>(CAPACITY) : null);
        }
    }
    private ResourceConstructionCache() {}

    public static synchronized void configure(boolean enabled) { configure(enabled, enabled); }
    public static synchronized void configure(boolean locations, boolean tags) {
        var current = tables;
        // Config watchers can replay an unchanged file, including immediately after first creation.
        // Do not discard live representatives when neither cache setting actually changed.
        if (locations == (current != null && current.locations != null)
                && tags == (current != null && current.tags != null)) return;
        tables = locations || tags ? new Tables(locations, tags) : null;
    }
    public static synchronized void clear() {
        if (tables != null) tables = new Tables(tables.locations != null, tables.tags != null);
    }

    public static boolean locationsEnabled() {
        var current = tables;
        return current != null && current.locations != null;
    }

    public static ResourceLocation location(String namespace, String path, Operation<ResourceLocation> constructor) {
        var current = tables;
        if (current == null || current.locations == null) return constructor.call(namespace, path);
        var cached = current.locations.find(namespace, path);
        // A hit has already passed the original validation. Construction occurs outside table locks.
        return cached != null ? cached : current.locations.intern(constructor.call(namespace, path));
    }

    /** One-argument native factories; avoid allocating a capturing adapter on every pair hit. */
    public static ResourceLocation pathLocation(String namespace, String path, Operation<ResourceLocation> factory) {
        var current = tables;
        if (current == null || current.locations == null) return factory.call(path);
        var cached = current.locations.find(namespace, path);
        return cached != null ? cached : current.locations.intern(factory.call(path));
    }

    public static ResourceLocation parsedLocation(String input, char separator, Operation<ResourceLocation> factory) {
        var current = tables;
        if (current == null || current.locations == null) return factory.call(input, separator);
        var cached = current.locations.findParsed(input, separator);
        if (cached != null) return cached;
        var result = current.locations.canonicalizeParsed(factory.call(input, separator));
        current.locations.rememberParsed(input, separator, result);
        return result;
    }

    public static void maintain() {
        var current = tables;
        if (current != null && current.locations != null) current.locations.cleanUp();
    }

    @SuppressWarnings("unchecked")
    public static <T> TagKey<T> tag(ResourceKey<? extends Registry<T>> registry,
                                  ResourceLocation location, Operation<TagKey<T>> constructor) {
        var current = tables;
        if (current == null || current.tags == null) return constructor.call(registry, location);
        int hash = 31 * System.identityHashCode(registry) + location.hashCode();
        int slot = (hash ^ (hash >>> 16)) & (CAPACITY - 1);
        var cached = current.tags.get(slot);
        if (cached != null && cached.registry() == registry && cached.location().equals(location)) return (TagKey<T>) cached;
        var result = constructor.call(registry, location);
        current.tags.set(slot, result);
        // The caller still feeds this candidate into vanilla's weak interner. Existing live
        // canonical objects remain authoritative, including after cache resets and collisions.
        return result;
    }
}
