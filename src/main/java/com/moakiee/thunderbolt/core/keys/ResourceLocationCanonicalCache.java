package com.moakiee.thunderbolt.core.keys;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.resources.ResourceLocation;

/** One generation of live canonical identifiers. Neither the table nor the shortcuts own an ID. */
final class ResourceLocationCanonicalCache {
    private static final int CAPACITY = 4096;
    private static final WeakCanonicalSet.Matcher<ResourceLocation, String, String> COMPONENTS =
            (value, namespace, path) -> value.getNamespace().equals(namespace) && value.getPath().equals(path);
    private final WeakCanonicalSet<ResourceLocation> canonical = new WeakCanonicalSet<>(CAPACITY,
            (left, right) -> COMPONENTS.matches(left, right.getNamespace(), right.getPath()));
    private final AtomicReferenceArray<ParsedAlias> parsed = new AtomicReferenceArray<>(CAPACITY);
    // The canonical table owns no full input strings. This separate weak alias index prevents
    // large, repeatedly parsed working sets from allocating substrings on every shortcut miss.
    private final WeakOpenHashMap<String, ResourceLocation> colonAliases = new WeakOpenHashMap<>();

    // Full input is only an alias: an RL does not retain the combined "namespace:path" string.
    // Losing this weak input must never remove its still-live representative from canonical.
    private record ParsedAlias(WeakReference<String> input, char separator, WeakReference<ResourceLocation> value) {}

    private static int slot(int hash) { return (hash ^ (hash >>> 16)) & (CAPACITY - 1); }

    ResourceLocation find(String namespace, String path) {
        int hash = 31 * namespace.hashCode() + path.hashCode();
        return canonical.get(hash, namespace, path, COMPONENTS);
    }

    ResourceLocation intern(ResourceLocation value) {
        if (value == null) return null; // Vanilla tryBuild/tryBySeparator validation failed.
        String namespace = value.getNamespace(), path = value.getPath();
        int hash = 31 * namespace.hashCode() + path.hashCode();
        return canonical.intern(value, hash);
    }

    /** Parsing may already have passed through a cached two-part factory. Avoid a second writer lock. */
    ResourceLocation canonicalizeParsed(ResourceLocation value) {
        if (value == null) return null;
        var cached = find(value.getNamespace(), value.getPath());
        return cached != null ? cached : intern(value);
    }

    ResourceLocation findParsed(String input, char separator) {
        var alias = parsed.get(slot(31 * input.hashCode() + separator));
        var cached = alias != null && alias.separator == separator && input.equals(alias.input.get())
                ? alias.value.get() : null;
        return cached != null || separator != ':' ? cached : colonAliases.get(input);
    }

    void rememberParsed(String input, char separator, ResourceLocation value) {
        if (value == null) return;
        int hash = 31 * value.getNamespace().hashCode() + value.getPath().hashCode();
        if (!canonical.seenForAlias(value, hash)) return;
        if (separator == ':') {
            colonAliases.cleanUp();
            colonAliases.putIfAbsent(input, value);
        }
        parsed.set(slot(31 * input.hashCode() + separator),
                new ParsedAlias(new WeakReference<>(input), separator, new WeakReference<>(value)));
    }

    void cleanUp() { canonical.cleanUp(); colonAliases.cleanUp(); }
}
