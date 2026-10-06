package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaterialFootprintCancellationTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void stopsWithinAWideRowThroughTheIndexAndPublicPlanner(boolean publicEntry, boolean sideOutputs) {
        check(publicEntry, sideOutputs, 0);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void doesNotPublishAnIndexAfterCancellationOnTheLastSlot(boolean publicEntry, boolean sideOutputs) {
        check(publicEntry, sideOutputs, 4095);
    }

    @Test
    void doesNotPublishWhenCancellationArrivesInItsFinalShortRecipe() {
        // A single input never reaches a periodic checkpoint. Once its key cancels the
        // final recipe, publication itself must reject the otherwise completed table.
        check(false, false, 0, 1);
    }

    private static void check(boolean publicEntry, boolean sideOutputs, int interruptAt) {
        check(publicEntry, sideOutputs, interruptAt, 4096);
    }

    private static void check(boolean publicEntry, boolean sideOutputs, int interruptAt, int width) {
        var probe = new Probe(sideOutputs, width, interruptAt);
        var target = new Key(-1, probe);
        var raw = new Key(-2, probe);
        var order = new ArrayList<Key>();
        order.add(target);
        order.add(raw);
        var inputs = new ArrayList<CraftInput<Key>>();
        var outputs = new ArrayList<CraftOutput<Key>>();
        var builder = CraftGraph.<Key>builder().stock(raw, 1);
        for (int i = 0; i < width; i++) {
            var key = new Key(i, probe);
            if (sideOutputs) {
                outputs.add(CraftOutput.of(key, 1));
            } else {
                inputs.add(CraftInput.of(key, 1));
                builder.stock(key, 1);
                order.add(key);
            }
        }
        if (sideOutputs) inputs.add(CraftInput.of(raw, 1));
        var pattern = new CraftPattern<>(target, 1, inputs, outputs, null);
        builder.pattern(pattern);
        // Equivalence is now requested only after the linear pass needs competing routes.
        if (publicEntry) builder.pattern(new CraftPattern<>(target, 1, inputs, outputs, null));
        var graph = builder.build();
        var selected = Map.of(target, List.of(pattern));
        var healthyIndex = MaterialFootprintIndex.build(graph, order, selected);
        var healthyPlan = CraftPlannerV2.plan(graph, target, 1);
        assertTrue(healthyPlan.feasible());
        assertEquals(Map.of(pattern, 1L), healthyPlan.firings());
        var originalIndex = Map.copyOf(healthyIndex);
        var originalStock = Map.copyOf(healthyPlan.usedStock());

        probe.armed = true;
        try {
            assertThrows(CancellationException.class, () -> {
                if (publicEntry) CraftPlannerV2.planDetailed(graph, target, 2);
                else MaterialFootprintIndex.build(graph, order, selected);
            });
            assertTrue(probe.triggered, "must cancel inside the intended footprint stage");
            assertTrue(probe.distinct > 0 && probe.distinct <= 255,
                    () -> "visited " + probe.distinct + " distinct slots after cancellation");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            probe.armed = false;
            Thread.interrupted();
        }
        assertEquals(originalIndex, healthyIndex);
        assertEquals(originalStock, healthyPlan.usedStock());
    }

    private static final class Probe {
        final boolean sideOutputs;
        final boolean[] visited;
        final int interruptAt;
        boolean armed, triggered;
        int distinct;

        Probe(boolean sideOutputs, int width, int interruptAt) {
            this.sideOutputs = sideOutputs;
            this.visited = new boolean[width];
            this.interruptAt = interruptAt;
        }

        void observe(int id) {
            if (!armed || id < 0) return;
            // Fixture setup and earlier compiler phases touch the same keys. Only the intended
            // extracted stage may trigger this cancellation, including when reached publicly.
            if (!triggered && id == interruptAt && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().endsWith("MaterialFootprintIndex") && frame.getMethodName().equals(
                            sideOutputs ? "build" : "materialFootprint")))) {
                triggered = true;
                Thread.currentThread().interrupt();
            }
            if (triggered && !visited[id]) {
                visited[id] = true;
                distinct++;
            }
        }
    }

    private record Key(int id, Probe probe) {
        @Override public int hashCode() { probe.observe(id); return id; }
    }
}
