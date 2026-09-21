package com.moakiee.thunderbolt.core.keys;

import appeng.api.stacks.AEItemKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Objects;
import java.util.Optional;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

/** Per-item aliases and content lookup weakly reference the actual key and its existing owned snapshot. */
public final class ComponentItemKeyCache {
    private final WeakCanonicalMap<Object, AEItemKey> keys;
    private final boolean staticDamageHooks;

    ComponentItemKeyCache(WeakCanonicalMap.IdentityTable<AEItemKey> identities, boolean staticDamageHooks) {
        this.keys = new WeakCanonicalMap<>(identities, ComponentItemKeyCache::canonicalPatch);
        this.staticDamageHooks = staticDamageHooks;
    }

    void cleanUp() { keys.cleanUp(); }

    public AEItemKey findIdentity(ItemStack source, Object patch, Object prototype) {
        var key = keys.findIdentity(patch, 0);
        return key != null && matchesIdentity(key, source, prototype) ? key : null;
    }

    /** Cheap content hints never decide equality: every candidate receives a full comparison. */
    public AEItemKey findValue(ItemStack source, Object prototype) {
        if (!(source.getComponents() instanceof SharedComponentPatch access)) return null;
        var components = access.thunderbolt$componentPatchView();
        var recent = keys.recentValue();
        AEItemKey existing = null;
        // Common repeated names need no fingerprint/table lookup. Different names are rejected
        // before allocating a component iterator or examining any deep payload.
        if (recent != null && components.containsKey(DataComponents.CUSTOM_NAME) && sameName(components, recent)
                && matchesSource(recent, source, prototype, components)) existing = recent;
        if (existing == null) {
            var signature = hintSignature(components);
            // Opaque-only patches have no cheap discriminator: hash once in native construction.
            if (signature == 0) return null;
            var hinted = keys.findIdentity(this, (int) signature);
            if (hinted != null && (hinted != recent || !components.containsKey(DataComponents.CUSTOM_NAME))
                    && matchesSource(hinted, source, prototype, components)) existing = hinted;
        }
        if (existing == null) return null;
        var identity = access.thunderbolt$copyOnWritePatchIdentity();
        // Already-shared inputs gain identity aliases. First-use hints allocate none.
        if (identity != null) existing = keys.findValue(identity, 0, new KeyProbe(existing));
        if (existing != null) access.thunderbolt$shareComponentPatch();
        return existing;
    }

    private boolean matchesSource(AEItemKey key, ItemStack source, Object prototype,
            Reference2ObjectMap<DataComponentType<?>, Optional<?>> components) {
        // No hook calls or normalized temporary stacks are needed when the hint itself missed.
        var metadata = staticDamageHooks ? source : KeyConstructionCache.normalizedStack(source);
        return matchesContent(key, source.getItem(), prototype, metadata.getDamageValue(), components);
    }

    private static boolean sameName(Reference2ObjectMap<DataComponentType<?>, Optional<?>> query, AEItemKey key) {
        var access = (SharedComponentPatch) key.getReadOnlyStack().getComponents();
        return Objects.equals(query.get(DataComponents.CUSTOM_NAME),
                access.thunderbolt$componentPatchView().get(DataComponents.CUSTOM_NAME));
    }

    /** A deliberately incomplete fingerprint. Collisions or L1 eviction only cause fallback. */
    private static long hintSignature(Reference2ObjectMap<DataComponentType<?>, Optional<?>> components) {
        var name = components.get(DataComponents.CUSTOM_NAME);
        var damage = components.get(DataComponents.DAMAGE);
        var model = components.get(DataComponents.CUSTOM_MODEL_DATA);
        var limit = components.get(DataComponents.MAX_STACK_SIZE);
        var opaque = components.get(DataComponents.CUSTOM_DATA);
        if (opaque == null) opaque = components.get(DataComponents.CONTAINER);
        if (opaque == null) opaque = components.get(DataComponents.ENCHANTMENTS);
        if (opaque == null) opaque = components.get(DataComponents.POTION_CONTENTS);
        if (name == null && damage == null && model == null && limit == null && opaque == null) return 0;
        // Component.hashCode also walks style/siblings and creates temporary arrays. Literal text
        // is a cheap discriminator; ignored style/siblings are still checked by full equality.
        int nameHash = name != null && name.orElse(null) instanceof Component component
                && component.getContents() instanceof PlainTextContents text ? text.text().hashCode() : 0;
        int hash = 31 * components.size() + nameHash;
        hash = 31 * hash + Objects.hashCode(damage);
        hash = 31 * hash + Objects.hashCode(model);
        hash = 31 * hash + Objects.hashCode(limit);
        // copy() also shares immutable component values after its patch map is detached. This is
        // only a hint: separately decoded equal values may miss and use the full native-hash index.
        hash = 31 * hash + (opaque == null ? 0 : System.identityHashCode(opaque.orElse(null)));
        return (1L << 32) | Integer.toUnsignedLong(hash);
    }

    /** A first-use miss never admits a value, but can still reuse an existing equal representative. */
    public AEItemKey reuseConstructed(AEItemKey created) {
        if (canonicalPatch(created) == null) return created;
        var existing = keys.findValue(new KeyProbe(created));
        return existing != null ? existing : created;
    }

    public AEItemKey getOrCreate(ItemStack ownedStack, Object patch, Object prototype,
                                 Operation<AEItemKey> constructor) {
        var recent = keys.recentValue();
        if (recent != null && recent.getReadOnlyStack() == ownedStack) return recent;
        var cached = keys.findIdentity(patch, 0);
        if (cached != null && (cached.getReadOnlyStack() == ownedStack
                || matchesIdentity(cached, ownedStack, prototype))) return cached;
        // The native key computes its hash once. Both lookup and insertion consume that cached
        // hash, so a true miss never traverses a deep component tree twice.
        var created = constructor.call(ownedStack);
        if (canonicalPatch(created) != patch
                || !(created.getReadOnlyStack().getComponents() instanceof SharedComponentPatch access)
                || access.thunderbolt$prototypeIdentity() != prototype) return created;
        var probe = new KeyProbe(created);
        var result = keys.intern(patch, 0, probe, () -> created,
                ComponentItemKeyCache::canonicalPatch, key -> probe.matches(canonicalPatch(key), key));
        var signature = hintSignature(((SharedComponentPatch) result.getReadOnlyStack().getComponents())
                .thunderbolt$componentPatchView());
        if (signature != 0) keys.rememberHint(this, (int) signature, result);
        return result;
    }

    private boolean matchesIdentity(AEItemKey key, ItemStack source, Object prototype) {
        if (key.getItem() != source.getItem()
                || !(key.getReadOnlyStack().getComponents() instanceof SharedComponentPatch access)
                || access.thunderbolt$prototypeIdentity() != prototype) return false;
        // Snapshot identity and normalized count/animation were validated by the alias stamp.
        if (staticDamageHooks) return true;
        var metadata = KeyConstructionCache.normalizedStack(source);
        return key.getFuzzySearchValue() == metadata.getDamageValue();
    }

    private static Object canonicalPatch(AEItemKey key) {
        var stack = key.getReadOnlyStack();
        return stack.getCount() == 1 && stack.getPopTime() == 0
                && stack.getComponents() instanceof SharedComponentPatch access
                ? access.thunderbolt$copyOnWritePatchIdentity() : null;
    }

    private static boolean sameComponents(Reference2ObjectMap<DataComponentType<?>, Optional<?>> query,
                                          Reference2ObjectMap<DataComponentType<?>, Optional<?>> stored) {
        if (query == stored) return true;
        if (query.size() != stored.size()) return false;
        var entries = Reference2ObjectMaps.fastIterator(query);
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!Objects.equals(entry.getValue(), stored.get(entry.getKey()))) return false;
        }
        return true;
    }

    private static boolean matchesContent(AEItemKey key, Item item, Object prototype, int damage,
            Reference2ObjectMap<DataComponentType<?>, Optional<?>> components) {
        return key.getItem() == item && key.getFuzzySearchValue() == damage
                && key.getReadOnlyStack().getComponents() instanceof SharedComponentPatch access
                && access.thunderbolt$prototypeIdentity() == prototype
                && sameComponents(components, access.thunderbolt$componentPatchView());
    }

    /** Short-lived query reuses native metadata; the AEItemKey layout stays unchanged. */
    private record KeyProbe(AEItemKey query) implements WeakCanonicalMap.Lookup<Object, AEItemKey> {
        @Override public int hash() {
            var access = (SharedComponentPatch) query.getReadOnlyStack().getComponents();
            return 31 * (31 * query.hashCode() + System.identityHashCode(access.thunderbolt$prototypeIdentity()))
                    + query.getFuzzySearchValue();
        }

        @Override public boolean matches(Object snapshot, AEItemKey key) {
            // Reject aliases of a supposedly read-only key that an addon has subsequently changed.
            if (snapshot == null || canonicalPatch(key) != snapshot || key.hashCode() != query.hashCode()) return false;
            var access = (SharedComponentPatch) query.getReadOnlyStack().getComponents();
            return matchesContent(key, query.getItem(), access.thunderbolt$prototypeIdentity(),
                    query.getFuzzySearchValue(), access.thunderbolt$componentPatchView());
        }
    }
}
