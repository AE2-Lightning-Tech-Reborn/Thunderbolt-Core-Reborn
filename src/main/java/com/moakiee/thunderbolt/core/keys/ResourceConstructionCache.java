package com.moakiee.thunderbolt.core.keys;

import java.util.concurrent.atomic.AtomicReferenceArray;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/** Reuses constructor results without taking over vanilla's factories, validation or interning. */
public final class ResourceConstructionCache {
    private static final int CAPACITY = 4096;
    private static volatile Tables tables;
    private record Tables(AtomicReferenceArray<ResourceLocation> locations, AtomicReferenceArray<TagKey<?>> tags) {
        private Tables(boolean locations, boolean tags) {
            this(locations ? new AtomicReferenceArray<>(CAPACITY) : null,
                    tags ? new AtomicReferenceArray<>(CAPACITY) : null);
        }
    }
    private ResourceConstructionCache() {}

    public static synchronized void configure(boolean enabled) { configure(enabled, enabled); }
    public static synchronized void configure(boolean locations, boolean tags) {
        tables = locations || tags ? new Tables(locations, tags) : null;
    }
    public static synchronized void clear() {
        if (tables != null) configure(tables.locations != null, tables.tags != null);
    }

    public static ResourceLocation location(String namespace, String path, Operation<ResourceLocation> constructor) {
        var current = tables;
        if (current == null || current.locations == null) return constructor.call(namespace, path);
        int hash = 31 * namespace.hashCode() + path.hashCode();
        int slot = (hash ^ (hash >>> 16)) & (CAPACITY - 1);
        var cached = current.locations.get(slot);
        if (cached != null && cached.getNamespace().equals(namespace) && cached.getPath().equals(path)) return cached;
        var result = constructor.call(namespace, path);
        current.locations.set(slot, result);
        return result;
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
