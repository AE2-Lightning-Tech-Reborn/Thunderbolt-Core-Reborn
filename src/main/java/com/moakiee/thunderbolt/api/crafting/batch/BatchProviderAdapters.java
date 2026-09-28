package com.moakiee.thunderbolt.api.crafting.batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * Global adapter definitions shared by every Thunderbolt batch CPU. Register during common setup.
 * Native {@link IBatchCraftingProvider} implementations always win. Otherwise the first matching
 * adapter wins, ordered by descending priority, then registry ID. Endpoints and jobs are never
 * stored here: resolution caching remains CPU-owned and honors each resolver's cache policy.
 */
public final class BatchProviderAdapters {
    private static volatile List<Entry> entries = List.of();

    private BatchProviderAdapters() {}

    public static void register(Identifier id, BatchProviderAdapter adapter) {
        register(id, 0, adapter);
    }

    /** Duplicate IDs are rejected. Built-in optional adapters use priority -100. */
    public static synchronized void register(Identifier id, int priority, BatchProviderAdapter adapter) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(adapter, "adapter");
        if (entries.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("Duplicate batch provider adapter: " + id);
        }
        var updated = new ArrayList<>(entries);
        updated.add(new Entry(id, priority, adapter));
        updated.sort(Comparator.comparingInt(Entry::priority).reversed().thenComparing(e -> e.id().toString()));
        entries = List.copyOf(updated);
    }

    /** Removes a definition and invalidates CPU resolution snapshots on their next lookup. */
    public static synchronized void unregister(Identifier id) {
        var updated = new ArrayList<>(entries);
        if (updated.removeIf(entry -> entry.id().equals(id))) entries = List.copyOf(updated);
    }

    /** Immutable snapshot; its identity changes whenever registrations change. */
    public static List<Entry> entries() { return entries; }

    public record Entry(Identifier id, int priority, BatchProviderAdapter adapter) {}
}
