package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SelfSeedReservationTest {
    private static final ReusableStockSource SOURCE = new ReusableStockSource("host", "template-loop");

    @Test
    void issue10MatrixConsumesOrdinaryTemplateWithoutDuplicatingPrivateSeed() {
        for (int templates : new int[] {0, 1, 2}) {
            for (int diamonds : new int[] {0, 7, 10}) {
                for (boolean ordinaryCopy : List.of(false, true)) {
                    for (boolean reverse : List.of(false, true)) {
                        var graph = templateGraph(1, templates, 1, diamonds, ordinaryCopy, reverse);
                        var plan = CraftPlannerV2.plan(graph, "target", 1);
                        assertTrue(plan.feasible(), () -> "templates=" + templates + ", diamonds="
                                + diamonds + ", ordinaryCopy=" + ordinaryCopy + ", reverse=" + reverse
                                + ", missing=" + plan.missing());
                        assertTrue(plan.missing().isEmpty());
                        assertTrue(CraftPlannerV2.plan(graph, "diamond", 1_000).feasible());
                        assertTemplateWitness(graph, plan, 1, 1);
                        if (templates > 0) {
                            assertEquals(0L, firingsFor(plan, "template"),
                                    "the stocked template must not trigger an unnecessary seven-diamond copy");
                            assertTrue(plan.usedReusableStock().isEmpty());
                        }
                    }
                }
            }
        }
    }

    @Test
    void partialAndAbsentPrivateSeedsKeepUpstreamDemandConsistent() {
        // 432 cases. A witness either consumes already sufficient templates, or reserves the
        // remaining startup seed, processes ore, copies templates, and finally crafts the target.
        for (int host = 0; host <= 2; host++) {
            for (int stock = 0; stock <= 3; stock++) {
                for (int seed = 1; seed <= 2; seed++) {
                    for (int requested : new int[] {1, 2, 5}) {
                        for (int diamonds : new int[] {0, 7, 10}) {
                            for (boolean reverse : List.of(false, true)) {
                                var graph = templateGraph(host, stock, seed, diamonds, false, reverse);
                                var plan = CraftPlannerV2.plan(graph, "target", requested);
                                boolean executable = stock >= requested || host + stock >= seed;
                                String context = "host=" + host + ", stock=" + stock + ", seed=" + seed
                                        + ", requested=" + requested + ", diamonds=" + diamonds
                                        + ", reverse=" + reverse + ", missing=" + plan.missing();
                                assertEquals(executable, plan.feasible(), context);
                                assertFalse(plan.budgetExhausted(), context);
                                if (executable) assertTemplateWitness(graph, plan, requested, seed);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void stockedOutputDoesNotNeedAnUnusedLoopOrItsMissingSeed() {
        for (boolean privateRoute : List.of(false, true)) {
            var seed = privateRoute ? CraftInput.returnedFrom("template", 2, SOURCE)
                    : CraftInput.returned("template", 2);
            var graph = CraftGraph.<String>builder()
                    .pattern("target", 1, List.of(CraftInput.of("template", 1)))
                    .pattern("template", 1, List.of(seed, CraftInput.of("diamond", 7)))
                    .stock("template", 1).build();
            var plan = CraftPlannerV2.plan(graph, "target", 1);
            assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
            assertEquals(Map.of("template", 1L), plan.usedStock());
            assertEquals(0L, firingsFor(plan, "template"));
            assertTrue(plan.usedReusableStock().isEmpty());
        }
    }

    @Test
    void fuzzyPrivateSeedLeavesOrdinaryExactTemplateConsumable() {
        var graph = templateGraph(0, 1, 1, 0, false, false);
        var builder = CraftGraph.<String>builder();
        for (String output : List.of("diamond", "template", "pick", "target"))
            graph.patternsFor(output).forEach(builder::pattern);
        for (String key : List.of("template", "stick", "ingot"))
            builder.stock(key, graph.stock(key));
        // Exactly thirteen diamonds: six for the picks and seven for one copy. A duplicate
        // ordinary seed reservation cannot be hidden by manufacturing an unnecessary second copy.
        graph = builder.stock("ore", 6).stock("deep-ore", 0).stock("diamond", 1)
                .reusableStock("host", "variant", 1)
                .reusableStockRoute(SOURCE, "template", List.of("variant"))
                .build();
        var plan = CraftPlannerV2.plan(graph, "target", 2);
        assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
        assertEquals(1L, plan.usedStock().get("template"));
        assertEquals(1L, firingsFor(plan, "template"));
        assertEquals(Map.of(new ReusableStockUsageKey<>(
                "host", "template-loop", "template-loop", "template", "variant"), 1L),
                plan.usedReusableStock());
        assertTemplateWitness(graph, plan, 2, 1);
    }

    @Test
    void earlierDedicatedBorrowCannotCountAgainAsAFeedbackSeed() {
        var first = new ReusableStockSource("host", "first");
        var loop = new ReusableStockSource("host", "loop");
        var graph = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(CraftInput.of("consumer", 1), CraftInput.of("crystal", 3)))
                .pattern("consumer", 1, List.of(CraftInput.returnedFrom("dust", 1, first)))
                .pattern("crystal", 1, List.of(CraftInput.returnedFrom("dust", 2, loop)))
                .pattern("dust", 1, List.of(CraftInput.of("crystal", 1)))
                .stock("crystal", 2).reusableStock("host", "dust", 1).build();
        var plan = CraftPlannerV2.plan(graph, "target", 1);
        assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
        assertEquals(Map.of("crystal", 2L), plan.usedStock());
        assertEquals(Map.of(new ReusableStockUsageKey<>("host", "first", "dust"), 1L),
                plan.usedReusableStock());
        assertEquals(2L, firingsFor(plan, "dust"), "both loop seeds must come from the converter");
        assertEquals(3L, firingsFor(plan, "crystal"));
    }

    @Test
    void initiallyCompleteHostStillRetainsItsPhysicalConverterFallback() {
        var first = new ReusableStockSource("host", "first");
        var loop = new ReusableStockSource("host", "loop");
        for (boolean auxiliary : List.of(false, true)) {
            for (boolean stocked : List.of(false, true)) {
                var converterInputs = auxiliary
                        ? List.of(CraftInput.of("crystal", 1), CraftInput.of("water", 1))
                        : List.of(CraftInput.of("crystal", 1));
                var builder = CraftGraph.<String>builder()
                        .pattern("target", 1, List.of(CraftInput.of("consumer", 1), CraftInput.of("crystal", 3)))
                        .pattern("consumer", 1, List.of(CraftInput.returnedFrom("dust", 1, first)))
                        .pattern("crystal", 1, List.of(CraftInput.returnedFrom("dust", 1, loop)))
                        .pattern("dust", 1, converterInputs)
                        .pattern("water", 1, List.of(CraftInput.of("ice", 1)))
                        .stock("ice", 1).reusableStock("host", "dust", 1);
                if (stocked) builder.stock("crystal", 1);
                var plan = CraftPlannerV2.plan(builder.build(), "target", 1);
                assertEquals(stocked, plan.feasible(), () -> "auxiliary=" + auxiliary
                        + ", stocked=" + stocked + ", missing=" + plan.missing());
                assertEquals(Map.of(new ReusableStockUsageKey<>("host", "first", "dust"), 1L),
                        plan.usedReusableStock());
                if (stocked) {
                    assertEquals(1L, plan.usedStock().get("crystal"));
                    assertEquals(1L, firingsFor(plan, "dust"));
                    assertEquals(3L, firingsFor(plan, "crystal"));
                    if (auxiliary) assertEquals(1L, plan.usedStock().get("ice"));
                }
            }
        }
    }

    @Test
    void returnedExactSeedCanServeAnotherRouteInTheSamePool() {
        for (boolean shared : List.of(false, true)) {
            var first = new ReusableStockSource("host", shared ? "template-loop" : "other", "first");
            var graph = CraftGraph.<String>builder()
                    .pattern("target", 1, List.of(CraftInput.of("consumer", 1), CraftInput.of("template", 2)))
                    .pattern("consumer", 1, List.of(CraftInput.returnedFrom("template", 1, first)))
                    .pattern("template", 1, List.of(CraftInput.returnedFrom("template", 1, SOURCE)))
                    .stock("template", 1).reusableStock("host", "template", 1).build();
            var plan = CraftPlannerV2.plan(graph, "target", 1);
            assertTrue(plan.feasible(), () -> "shared=" + shared + ", missing=" + plan.missing());
            assertEquals(1L, plan.usedReusableStock().values().stream().mapToLong(Long::longValue).sum());
            if (!shared) {
                assertEquals(1L, plan.usedStock().get("template"));
                assertEquals(2L, firingsFor(plan, "template"),
                        "a dedicated pool must bootstrap its own seed");
            } else {
                assertTrue(plan.usedStock().getOrDefault("template", 0L) <= 1L);
                assertTrue(firingsFor(plan, "template")
                        + plan.usedStock().getOrDefault("template", 0L) >= 2L);
            }
        }
    }

    @Test
    void selfSeedReservationsRespectDedicatedPoolsAndFuzzyRoutingOwnership() {
        for (String ownership : List.of("same-route", "shared-pool", "dedicated-pool")) {
            var first = ownership.equals("same-route") ? SOURCE : new ReusableStockSource(
                    "host", ownership.equals("shared-pool") ? "template-loop" : "other", "first");
            for (String actual : List.of("template", "variant")) {
                for (int host = 0; host <= 2; host++) {
                    for (int stock = 0; stock <= 1; stock++) {
                        var graph = CraftGraph.<String>builder()
                                .pattern("target", 1, List.of(CraftInput.of("consumer", 1), CraftInput.of("template", 2)))
                                .pattern("consumer", 1, List.of(CraftInput.returnedFrom("template", 1, first)))
                                .pattern("template", 1, List.of(CraftInput.returnedFrom("template", 1, SOURCE)))
                                .stock("template", stock).reusableStock("host", actual, host)
                                .reusableStockRoute(first, "template", List.of(actual))
                                .reusableStockRoute(SOURCE, "template", List.of(actual)).build();
                        var plan = CraftPlannerV2.plan(graph, "target", 1);
                        boolean shared = ownership.equals("same-route")
                                || (ownership.equals("shared-pool") && actual.equals("template"));
                        boolean executable = stock > 0 || host >= 2 || (host == 1 && shared);
                        assertEquals(executable, plan.feasible(), "ownership=" + ownership
                                + ", actual=" + actual + ", host=" + host + ", stock=" + stock
                                + ", missing=" + plan.missing());
                        assertTrue(plan.usedStock().getOrDefault("template", 0L) <= stock);
                        assertTrue(plan.usedReusableStock().values().stream().mapToLong(Long::longValue).sum() <= host);
                    }
                }
            }
        }
    }

    private static CraftGraph<String> templateGraph(
            int host, int templates, int seed, int diamonds, boolean ordinaryCopy, boolean reverse) {
        var builder = CraftGraph.<String>builder()
                .stock("ore", 16_253).stock("deep-ore", 30_715)
                .stock("diamond", diamonds).stock("template", templates)
                .stock("stick", 100).stock("ingot", 100)
                .reusableStock("host", "template", host)
                .reusableStockRoute(SOURCE, "template", List.of("template"))
                .pattern("diamond", 2, List.of(CraftInput.of("ore", 1)))
                .pattern("diamond", 1, List.of(CraftInput.of("deep-ore", 1)))
                .pattern("pick", 1, List.of(CraftInput.of("diamond", 3), CraftInput.of("stick", 2)))
                .pattern("template", 1, List.of(CraftInput.returnedFrom("template", seed, SOURCE),
                        CraftInput.of("diamond", 7)));
        if (ordinaryCopy) builder.pattern("template", 2, List.of(
                CraftInput.of("template", 1), CraftInput.of("diamond", 7)));
        builder.pattern("target", 1, reverse
                ? List.of(CraftInput.of("pick", 1), CraftInput.of("template", 1), CraftInput.of("ingot", 1))
                : List.of(CraftInput.of("template", 1), CraftInput.of("pick", 1), CraftInput.of("ingot", 1)));
        return builder.build();
    }

    /** Independent resource/startup certificate for this fixture's ore -> copy/pick -> target order. */
    private static void assertTemplateWitness(CraftGraph<String> graph, CraftPlan<String> plan,
            long requested, long seed) {
        assertTrue(plan.missing().isEmpty());
        plan.usedStock().forEach((key, used) -> assertTrue(used >= 0 && used <= graph.stock(key), key));
        long hostUsed = 0L;
        for (var entry : plan.usedReusableStock().entrySet()) {
            var key = entry.getKey();
            assertTrue(graph.reusableStockCandidates(SOURCE, "template").contains(key.actualKey()));
            assertTrue(entry.getValue() <= graph.reusableStock(key.storageScope(), key.actualKey()));
            hostUsed += entry.getValue();
        }
        long copied = firingsFor(plan, "template");
        long networkSeed = copied == 0 ? 0 : Math.max(0L, seed - hostUsed);
        assertTrue(plan.usedStock().getOrDefault("template", 0L) >= networkSeed);
        assertTrue(plan.usedStock().getOrDefault("template", 0L) + copied - networkSeed >= requested);
        long diamonds = plan.usedStock().getOrDefault("diamond", 0L);
        for (var entry : plan.firings().entrySet()) {
            if (entry.getKey().output().equals("diamond")) {
                diamonds += entry.getKey().outputAmount() * entry.getValue();
                String ore = entry.getKey().inputs().get(0).key();
                assertEquals(entry.getValue(), plan.usedStock().get(ore));
            }
        }
        assertTrue(diamonds >= 7 * copied + 3 * requested);
        assertEquals(requested, firingsFor(plan, "pick"));
        assertEquals(requested, firingsFor(plan, "target"));
        assertEquals(2 * requested, plan.usedStock().get("stick"));
        assertEquals(requested, plan.usedStock().get("ingot"));
    }

    private static long firingsFor(CraftPlan<String> plan, String output) {
        return plan.firings().entrySet().stream().filter(entry -> entry.getKey().output().equals(output))
                .mapToLong(Map.Entry::getValue).sum();
    }
}
