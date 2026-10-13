package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialDagSnapshotTest {
    @Test
    void largeCertificateOwnsImmutableStockBalancesAndPatternCounts() {
        var builder = CraftGraph.<String>builder();
        var rootInputs = new ArrayList<CraftInput<String>>();
        var counts = new IdentityHashMap<CraftPattern<String>, Long>();
        for (int i = 0; i < 512; i++) {
            String raw = "raw" + i, output = "part" + i;
            var pattern = new CraftPattern<>(output, 1, List.of(CraftInput.of(raw, 1)), null);
            builder.stock(raw, 3).pattern(pattern);
            rootInputs.add(CraftInput.of(output, 1));
            counts.put(pattern, 3L);
        }
        var root = new CraftPattern<>("T", 1, rootInputs, null);
        builder.pattern(root);
        counts.put(root, 3L);
        var graph = builder.build();
        var plan = MaterialDagReplay.tryPlan(graph, counts, "T", 3);
        assertNotNull(plan);
        assertTrue(plan.feasible());
        var expectedCounts = new IdentityHashMap<>(counts);
        counts.clear();
        assertEquals(expectedCounts, plan.firings());
        assertEquals(512, plan.usedStock().size());
        plan.usedStock().forEach((key, amount) -> assertEquals(graph.stock(key), amount));
        assertEquals(Map.of(), plan.missing());
        assertEquals(1025, plan.grossDemand().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.firings().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.usedStock().put("raw0", 0L));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.grossDemand().entrySet().iterator().next().setValue(0L));
        assertEquals(expectedCounts, plan.firings());
        assertEquals(3L, plan.usedStock().get("raw0"));
        assertEquals(3L, plan.grossDemand().get("T"));
    }
}
