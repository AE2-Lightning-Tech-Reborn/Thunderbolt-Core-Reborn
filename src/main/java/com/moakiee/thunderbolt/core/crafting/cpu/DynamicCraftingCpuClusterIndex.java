package com.moakiee.thunderbolt.core.crafting.cpu;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

/** Identity index for nodes that can dynamically expose an extended crafting CPU cluster. */
public final class DynamicCraftingCpuClusterIndex<P, C> {
    private final Set<P> providers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<C> clusters = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<C> clustersView = Collections.unmodifiableSet(clusters);
    private final Set<C> refreshedClusters = Collections.newSetFromMap(new IdentityHashMap<>());

    public boolean addProvider(P provider) {
        return providers.add(provider);
    }

    public boolean removeProvider(P provider) {
        return providers.remove(provider);
    }

    public void replaceProviders(Iterable<? extends P> replacements) {
        providers.clear();
        for (var provider : replacements) {
            providers.add(provider);
        }
    }

    public boolean refresh(
            Function<? super P, @Nullable ? extends C> resolver,
            Consumer<? super C> onAdded) {
        refreshedClusters.clear();
        boolean addedCluster = false;
        for (var provider : providers) {
            var cluster = resolver.apply(provider);
            if (cluster != null && refreshedClusters.add(cluster) && !addedCluster) {
                addedCluster = !clusters.contains(cluster);
            }
        }

        // Providers must still be resolved on every refresh: their exposed cluster can change
        // without a node event. No new identity and the same size prove that both sets are equal;
        // stable snapshots need no second scan or redundant set writes.
        if (!addedCluster && clusters.size() == refreshedClusters.size()) return false;
        clusters.retainAll(refreshedClusters);
        for (var cluster : refreshedClusters) {
            if (clusters.add(cluster)) {
                onAdded.accept(cluster);
            }
        }
        return true;
    }

    public Set<C> clusters() {
        return clustersView;
    }
}
