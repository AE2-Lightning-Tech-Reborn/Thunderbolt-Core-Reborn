package com.moakiee.thunderbolt.core.crafting.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class DynamicCraftingCpuClusterIndexTest {
    @Test
    void observesActivationReplacementAndSuspensionWithoutReplacingProvider() {
        var provider = new MutableProvider();
        var first = new Object();
        var second = new Object();
        var added = new ArrayList<Object>();
        var index = new DynamicCraftingCpuClusterIndex<MutableProvider, Object>();

        index.addProvider(provider);
        assertFalse(index.refresh(current -> current.cluster, added::add));

        provider.cluster = first;
        assertTrue(index.refresh(current -> current.cluster, added::add));
        assertSame(first, index.clusters().iterator().next());
        assertEquals(1, added.size());

        provider.cluster = second;
        assertTrue(index.refresh(current -> current.cluster, added::add));
        assertSame(second, index.clusters().iterator().next());
        assertEquals(2, added.size());

        provider.cluster = null;
        assertTrue(index.refresh(current -> current.cluster, added::add));
        assertTrue(index.clusters().isEmpty());
    }

    @Test
    void deduplicatesSharedClusterByIdentity() {
        var cluster = new Object();
        var first = new MutableProvider();
        var second = new MutableProvider();
        first.cluster = cluster;
        second.cluster = cluster;
        var added = new ArrayList<Object>();
        var index = new DynamicCraftingCpuClusterIndex<MutableProvider, Object>();

        index.replaceProviders(java.util.List.of(first, second));
        assertTrue(index.refresh(current -> current.cluster, added::add));
        assertEquals(1, index.clusters().size());
        assertEquals(1, added.size());
    }

    @Test
    void stableRefreshStillResolvesEveryProviderAndDoesNotRepeatCallbacks() {
        var first = new MutableProvider();
        var second = new MutableProvider();
        first.cluster = new Object();
        var index = new DynamicCraftingCpuClusterIndex<MutableProvider, Object>();
        var added = new ArrayList<Object>();
        var calls = new int[1];
        index.replaceProviders(java.util.List.of(first, second));
        assertTrue(index.refresh(provider -> provider.cluster, added::add));
        var liveView = index.clusters();

        for (int i = 0; i < 20; i++) {
            assertFalse(index.refresh(provider -> {
                calls[0]++;
                return provider.cluster;
            }, added::add));
        }
        assertEquals(40, calls[0]);
        assertEquals(1, added.size());

        second.cluster = new Object();
        assertTrue(index.refresh(provider -> provider.cluster, added::add));
        assertSame(liveView, index.clusters());
        assertEquals(2, liveView.size());
        assertEquals(2, added.size());
    }

    @Test
    void equalButDistinctReplacementIsStillANewCluster() {
        var first = new String("same-value");
        var replacement = new String("same-value");
        var provider = new MutableProvider();
        provider.cluster = first;
        var index = new DynamicCraftingCpuClusterIndex<MutableProvider, Object>();
        var added = new ArrayList<Object>();
        index.addProvider(provider);
        assertTrue(index.refresh(current -> current.cluster, added::add));

        provider.cluster = replacement;
        assertTrue(index.refresh(current -> current.cluster, added::add));
        assertSame(replacement, index.clusters().iterator().next());
        assertSame(replacement, added.get(1));
        assertFalse(index.refresh(current -> current.cluster, added::add));
        assertEquals(2, added.size());
    }

    @Test
    void removingOneProviderPreservesItsSharedClusterUntilTheLastProviderLeaves() {
        var first = new MutableProvider();
        var second = new MutableProvider();
        first.cluster = second.cluster = new Object();
        var index = new DynamicCraftingCpuClusterIndex<MutableProvider, Object>();
        var added = new ArrayList<Object>();
        index.replaceProviders(java.util.List.of(first, second));
        assertTrue(index.refresh(provider -> provider.cluster, added::add));

        index.removeProvider(first);
        assertFalse(index.refresh(provider -> provider.cluster, added::add));
        assertEquals(1, index.clusters().size());
        index.removeProvider(second);
        assertTrue(index.refresh(provider -> provider.cluster, added::add));
        assertTrue(index.clusters().isEmpty());
        assertFalse(index.refresh(provider -> provider.cluster, added::add));
        assertEquals(1, added.size());
    }

    @Test
    void randomizedRefreshMatchesAnIndependentIdentityListOracle() {
        var random = new Random(0x54424350L);
        var pool = new Object[16];
        for (int i = 0; i < pool.length; i++) pool[i] = new String("equal-cluster");
        var providers = new ArrayList<MutableProvider>();
        for (int i = 0; i < 40; i++) providers.add(new MutableProvider());
        var index = new DynamicCraftingCpuClusterIndex<MutableProvider, Object>();
        index.replaceProviders(providers);
        List<Object> previous = List.of();

        for (int step = 0; step < 1_000; step++) {
            for (int mutation = 0; mutation < 3; mutation++) {
                providers.get(random.nextInt(providers.size())).cluster = random.nextBoolean()
                        ? pool[random.nextInt(pool.length)] : null;
            }
            var expected = new ArrayList<Object>();
            for (var provider : providers) {
                var cluster = provider.cluster;
                if (cluster != null && expected.stream().noneMatch(value -> value == cluster)) {
                    expected.add(cluster);
                }
            }
            var before = previous;
            var expectedAdded = expected.stream()
                    .filter(cluster -> before.stream().noneMatch(value -> value == cluster)).toList();
            boolean expectedChange = !expectedAdded.isEmpty() || expected.size() != before.size();
            var actualAdded = new ArrayList<Object>();
            assertEquals(expectedChange, index.refresh(provider -> provider.cluster, actualAdded::add));
            assertEquals(expected.size(), index.clusters().size());
            for (var cluster : index.clusters()) {
                assertTrue(expected.stream().anyMatch(value -> value == cluster));
            }
            assertEquals(expectedAdded.size(), actualAdded.size());
            for (var cluster : actualAdded) {
                assertTrue(expectedAdded.stream().anyMatch(value -> value == cluster));
            }
            previous = expected;
        }
    }

    private static final class MutableProvider {
        private Object cluster;
    }
}
