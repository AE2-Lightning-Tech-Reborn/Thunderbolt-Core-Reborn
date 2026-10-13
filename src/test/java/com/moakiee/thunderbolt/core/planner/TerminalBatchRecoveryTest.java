package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class TerminalBatchRecoveryTest {
    @Test
    void publicRecoveryFindsARealMissingWitnessBeyondTheOldReachableWorkGate() {
        // Frozen terminal audit seed 51032026, sample 788: the old public planner
        // reported missing, although p15*2 + p43*2 supplies 84 with R14/S16.
        var fixture = sample788();
        assertEquals(48, fixture.routes().size());
        assertTrue(CraftPlannerV2.reachableWorkEstimate(fixture.graph(), "T")
                > SmallConservativeSearch.MAX_WORK);
        assertNull(SmallConservativeSearch.tryPlan(fixture.graph(), "T", fixture.amount(), 4096));
        assertCertified(fixture, recover(fixture), 4);

        var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", fixture.amount());
        assertCertified(fixture, result.plan(), 4);
        assertEquals(48, result.diagnostics().reachablePatterns());
        assertTrue(result.diagnostics().consumedSearchBudget() <= result.diagnostics().configuredSearchBudget());
    }

    @Test
    void publicOptionalTimeoutPreservesTheOriginalMissingWitness() {
        // Every route has exactly the same unavoidable raw shortage. This keeps the
        // core witness stable even when the checkpoint adds overhead to optional work.
        var routes = new ArrayList<CraftPattern<String>>();
        for (int i = 0; i < 64; i++) routes.add(route(1, 1));
        var fixture = fixture(Map.of("R", 0L), routes, 1);
        assertTrue(CraftPlannerV2.reachableWorkEstimate(fixture.graph(), "T")
                > SmallConservativeSearch.MAX_WORK);
        var expected = CraftPlannerV2.planDetailed(fixture.graph(), "T", fixture.amount());
        assertTrue(expected.plan().supported());
        assertFalse(expected.plan().feasible());
        assertEquals(Map.of("R", 1L), expected.plan().missing());
        assertTrue(expected.plan().usedStock().isEmpty());
        assertEquals(1, expected.plan().firings().size());
        assertEquals(1L, expected.plan().firings().values().iterator().next());
        assertTrue(routes.stream().anyMatch(route -> route == expected.plan().firings().keySet().iterator().next()));
        assertFalse(expected.plan().budgetExhausted());
        assertFalse(expected.diagnostics().searchCutoff());

        var checkpoint = new TerminalCheckpoint(null);
        PlanningResult<String> result;
        try (var ignored = PlanningCancellation.bind(checkpoint)) {
            result = CraftPlannerV2.planDetailed(fixture.graph(), "T", fixture.amount());
        }
        assertTrue(checkpoint.enteredRecovery);
        // Timing out the helper must retain the same original pattern identity and
        // complete missing witness; wall-clock-dependent sample788 counts are irrelevant.
        assertTrue(result.plan().supported());
        assertFalse(result.plan().feasible());
        assertEquals(expected.plan().firings(), result.plan().firings());
        assertEquals(expected.plan().usedStock(), result.plan().usedStock());
        assertEquals(expected.plan().missing(), result.plan().missing());
        assertEquals(expected.plan().usedReusableStock(), result.plan().usedReusableStock());
        assertEquals(expected.plan().budgetExhausted(), result.plan().budgetExhausted());
        assertEquals(expected.diagnostics().searchCutoff(), result.diagnostics().searchCutoff());
        assertTrue(result.diagnostics().consumedSearchBudget() < result.diagnostics().configuredSearchBudget());
    }

    @Test
    void publicExternalCancellationAtTheTerminalCheckpointPropagates() {
        var fixture = sample788();
        var cancellation = new CancellationException("cancel terminal recovery");
        var checkpoint = new TerminalCheckpoint(cancellation);
        try (var ignored = PlanningCancellation.bind(checkpoint)) {
            assertSame(cancellation, assertThrows(CancellationException.class,
                    () -> CraftPlannerV2.planDetailed(fixture.graph(), "T", fixture.amount())));
        }
        assertTrue(checkpoint.enteredRecovery);
        assertEquals(15, fixture.graph().stock("R"));
        assertEquals(21, fixture.graph().stock("S"));
    }

    @Test
    void repeatedRegistrationsAboveSixtyFourStillReachPublicRecovery() {
        // The seventeen identities of sample 855 require a three-route witness.
        // Registration count is 68; it must not replace the distinct-identity guard.
        var original = sample855();
        var registrations = new ArrayList<CraftPattern<String>>();
        for (var route : original.routes()) for (int copy = 0; copy < 4; copy++) registrations.add(route);
        var fixture = fixture(original.stock(), registrations, original.amount());
        assertCertified(fixture, recover(fixture), 4);
        var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", fixture.amount());
        assertTrue(result.diagnostics().reachablePatterns() > 64);
        assertCertified(fixture, result.plan(), 4);
        assertEquals(3, result.plan().firings().size());
    }

    @Test
    void upstreamDuplicateRegistrationsDoNotAdmitASingleRouteTarget() {
        var grow = new CraftPattern<>("B", 2, List.of(CraftInput.of("R", 1)), new Object());
        var builder = CraftGraph.<String>builder().stock("R", 1)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("R", 1)));
        for (int copy = 0; copy < 16; copy++) builder.pattern(grow);
        var graph = builder.build();
        var result = CraftPlannerV2.planDetailed(graph, "T", 3);

        assertFalse(result.plan().feasible());
        assertTrue(result.diagnostics().reachablePatterns() >= 17,
                "upstream registrations alone used to satisfy the cheap terminal filter");
        assertEquals(4, result.diagnostics().reachableItems());
        assertEquals(1, graph.patternsFor("T").size());
        assertFalse(TerminalBatchRecovery.hasBoundedFootprint(graph, "T", result.diagnostics()));

        var workCalls = new AtomicInteger();
        assertNull(TerminalBatchRecovery.tryPlan(graph, "T", 3, 4096, work -> {
            workCalls.incrementAndGet();
            return true;
        }));
        assertEquals(0, workCalls.get(),
                "a narrow target must decline before spending the session's recovery budget");
        assertEquals(1, graph.stock("R"));
    }

    @Test
    void exhaustedOldQueueDoesNotManufactureAnAdditionalBudgetRejection() {
        var route = route(2, 1);
        var builder = CraftGraph.<String>builder();
        for (int copy = 0; copy < 17; copy++) builder.pattern(route);
        var result = CraftPlannerV2.planDetailed(builder.build(), "T", 1, 256, 1);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("R", 1L), result.plan().missing());
        assertEquals(1, result.diagnostics().consumedSearchBudget());
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
    }

    @Test
    void publicMultiUnitRecoveryRefusalMarksCutoffAndPreservesTheCoreMissingWitness()
            throws ReflectiveOperationException {
        var routes = new ArrayList<CraftPattern<String>>();
        for (int i = 0; i < 64; i++) routes.add(route(1, 1));
        var fixture = fixture(Map.of("R", 0L), routes, 1);
        int reachableWork = CraftPlannerV2.reachableWorkEstimate(fixture.graph(), "T");
        assertTrue(reachableWork > SmallConservativeSearch.MAX_WORK,
                "the old small recovery must not consume this test's allowance");

        // Capture the real pre-recovery witness with the same explicit budget, without
        // expiring a clock or changing the core planner's missing-refinement behavior.
        var core = CraftPlannerV2.class.getDeclaredMethod("planCore", CraftGraph.class,
                Object.class, long.class, int.class, int.class, int.class,
                CraftPlannerV2.PlanningSession.class);
        core.setAccessible(true);
        @SuppressWarnings("unchecked")
        var original = (PlanningResult<String>) core.invoke(null, fixture.graph(), "T", 1L,
                256, 2, reachableWork, new CraftPlannerV2.PlanningSession<String>());
        assertTrue(original.plan().supported());
        assertFalse(original.plan().feasible());
        assertEquals(Map.of("R", 1L), original.plan().missing());
        assertEquals(0, original.diagnostics().consumedSearchBudget());
        assertFalse(original.plan().budgetExhausted());
        assertFalse(original.diagnostics().searchCutoff());

        var rejected = CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, 256, 2);
        assertEquals(2, rejected.diagnostics().configuredSearchBudget());
        assertEquals(1, rejected.diagnostics().consumedSearchBudget(),
                "one unit remains: the two-unit input admission was refused atomically");
        assertTrue(rejected.plan().budgetExhausted());
        assertTrue(rejected.diagnostics().searchCutoff());
        assertEquals(original.plan().supported(), rejected.plan().supported());
        assertEquals(original.plan().feasible(), rejected.plan().feasible());
        assertEquals(original.plan().firings(), rejected.plan().firings());
        assertEquals(original.plan().usedStock(), rejected.plan().usedStock());
        assertEquals(original.plan().usedReusableStock(), rejected.plan().usedReusableStock());
        assertEquals(original.plan().missing(), rejected.plan().missing());
        assertEquals(original.plan().grossDemand(), rejected.plan().grossDemand());
        assertEquals(original.plan().itemsProcessed(), rejected.plan().itemsProcessed());
        assertEquals(0, fixture.graph().stock("R"));
    }

    @Test
    void fourNecessaryRoutesSharingOneSourceKeepTheirOriginalIdentities() {
        var source = new Object();
        var necessary = new ArrayList<CraftPattern<String>>();
        for (String raw : List.of("R", "S", "U", "V"))
            necessary.add(new CraftPattern<>("T", 2, List.of(CraftInput.of(raw, 1)), source));
        var fixture = fixture(Map.of("R", 1L, "S", 1L, "U", 1L, "V", 1L), wide(17, necessary), 8);
        var result = recover(fixture);
        assertCertified(fixture, result, 4);
        assertEquals(4, result.firings().size(), "each raw unit can support only its own route");
        for (var route : necessary) {
            assertSame(source, route.source());
            assertEquals(1L, result.firings().get(route));
        }
        assertEquals(fixture.stock(), result.usedStock());
    }

    @Test
    void targetInventoryIsAppliedOnceAndReturnedWithNormalizedDraw() {
        var producer = route(4, 1);
        var fixture = fixture(Map.of("R", 1L, "T", 4L), wide(17, List.of(producer)), 7);
        var result = recover(fixture);
        assertCertified(fixture, result, 1);
        assertEquals(Map.of(producer, 1L), result.firings());
        assertEquals(Map.of("R", 1L, "T", 3L), result.usedStock());
        assertEquals(4, fixture.graph().stock("T"));

        var onlyInventory = fixture(Map.of("R", 0L, "T", 7L), fixture.routes(), 7);
        var stocked = recover(onlyInventory);
        assertCertified(onlyInventory, stocked, 0);
        assertEquals(Map.of("T", 7L), stocked.usedStock());
    }

    @Test
    void duplicateRawSlotsAreAggregatedForDominanceAndCertification() {
        var split = new CraftPattern<>("T", 5,
                List.of(CraftInput.of("R", 2), CraftInput.of("R", 3)), "split");
        var fixture = fixture(Map.of("R", 10L), wide(17, List.of(split)), 10);
        var result = recover(fixture);
        assertCertified(fixture, result, 2);
        assertEquals(Map.of(split, 2L), result.firings());
        assertEquals(Map.of("R", 10L), result.usedStock());
        assertNull(recover(fixture(Map.of("R", 9L), fixture.routes(), 10)),
                "the two slots must consume five raw units together");
    }

    @Test
    void dominantLargerOutputGetsAValidPrimaryDemandCertificate() {
        var small = route(1, 2);
        var large = route(3, 1);
        var fixture = fixture(Map.of("R", 6L), wide(17, List.of(small, large)), 3);
        assertNull(MaterialDagReplay.tryPlan(fixture.graph(), Map.of(large, 3L), "T", 3),
                "blindly replacing three small firings overproduces unsupported primary batches");
        var result = recover(fixture);
        assertCertified(fixture, result, 1);
        assertEquals(Map.of(large, 1L), result.firings());
    }

    @Test
    void exactAndLegacyInputDisagreementDeclinesInsteadOfNarrowingTheDomain() {
        for (long[] amounts : new long[][] {{1, 2}, {2, 1}}) {
            var input = new CraftInput<>("R", amounts[0], false, CraftInput.INFINITE_USES,
                    null, null, BigInteger.valueOf(amounts[1]));
            var malformed = new CraftPattern<>("T", 2, List.of(input), null);
            assertNull(recover(fixture(Map.of("R", 4L), wide(17, List.of(malformed)), 2)));
        }
    }

    @Test
    void specialInputsByproductsInputlessAndSelfFeedingRoutesDecline() {
        var alternatives = List.of(
                new CraftPattern<>("T", 2, List.of(CraftInput.returned("R", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.finiteUse("R", 1, 2)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.consumedReturning("R", 1, "empty")), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.returnedFrom("R", 1,
                        new ReusableStockSource("host", "pool"))), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.of("R", 1)),
                        List.of(CraftOutput.of("side", 1)), null),
                new CraftPattern<>("T", BigInteger.TWO, List.of(CraftInput.of("R", 1)),
                        List.of(), null, List.of(List.of(CraftInput.of("R", 1)))),
                new CraftPattern<>("T", 2, List.of(CraftInput.of("T", 1)), null),
                new CraftPattern<String>("T", 2, List.of(), null));
        for (var alternative : alternatives)
            assertNull(recover(fixture(Map.of("R", 4L, "T", 1L), wide(17, List.of(alternative)), 3)));

        var fixture = fixture(Map.of("R", 4L), wide(17, List.of(route(2, 1))), 2);
        var builder = CraftGraph.<String>builder().stock("R", 4).stock("ore", 4);
        fixture.routes().forEach(builder::pattern);
        builder.pattern("R", 1, List.of(CraftInput.of("ore", 1)));
        assertNull(TerminalBatchRecovery.tryPlan(builder.build(), "T", 2, 4096, new WorkBudget(4096)),
                "a stocked key with a producer is not terminal inventory");
    }

    @Test
    void numericInputAndOutputBoundsAreCheckedBeforeProjection() {
        var atInputLimit = new CraftPattern<>("T", 2,
                List.of(CraftInput.of("R", 128), CraftInput.of("R", 128)), null);
        var valid = fixture(Map.of("R", 256L), wide(17, List.of(atInputLimit)), 2);
        assertCertified(valid, recover(valid), 1);

        var alternatives = List.of(
                route(257, 1),
                new CraftPattern<>("T", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                        List.of(CraftInput.of("R", 1)), List.of(), null),
                route(2, 257),
                new CraftPattern<>("T", 2,
                        List.of(CraftInput.of("R", 128), CraftInput.of("R", 129)), null));
        for (var alternative : alternatives)
            assertNull(recover(fixture(Map.of("R", 256L), wide(17, List.of(alternative)), 2)));

        var atOutputLimit = fixture(Map.of("R", 1L), wide(17, List.of(route(256, 1))), 256);
        assertCertified(atOutputLimit, recover(atOutputLimit), 1);
        for (long amount : new long[] {0, -1, 257, Long.MAX_VALUE}) {
            var calls = new AtomicInteger();
            assertNull(TerminalBatchRecovery.tryPlan(atOutputLimit.graph(), "T", amount, 4096,
                    work -> { calls.incrementAndGet(); return true; }));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void originalStockDomainIncludesTargetAndKeysOnlyDeletedRoutesConsume() {
        var good = route(2, 1);
        var dominated = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("R", 1), CraftInput.of("S", 1)), null);
        var routes = wide(17, List.of(good, dominated));
        var atLimit = fixture(Map.of("R", 1L, "S", 255L), routes, 2);
        assertCertified(atLimit, recover(atLimit), 1);
        assertNull(recover(fixture(Map.of("R", 1L, "S", 256L), routes, 2)),
                "removing the only S consumer must not remove S from the original stock bound");
        assertNull(recover(fixture(Map.of("R", 1L, "S", 255L, "T", 1L), routes, 2)),
                "target stock participates in the same total bound");

        var builder = CraftGraph.<String>builder().stockExact("R", BigInteger.valueOf(257));
        routes.forEach(builder::pattern);
        assertNull(TerminalBatchRecovery.tryPlan(builder.build(), "T", 2, 4096, new WorkBudget(4096)));
    }

    @Test
    void resourceCountAndNondominatedFrontierBoundsDeclineWithoutDiscardingRoutes() {
        var fiveInputs = new CraftPattern<>("T", 2, List.of(CraftInput.of("R", 1),
                CraftInput.of("S", 1), CraftInput.of("U", 1), CraftInput.of("V", 1),
                CraftInput.of("W", 1)), null);
        assertNull(recover(fixture(Map.of("R", 1L, "S", 1L, "U", 1L, "V", 1L, "W", 1L),
                wide(17, List.of(fiveInputs)), 2)));

        var nondominated = new ArrayList<CraftPattern<String>>();
        for (int r = 1; r <= 17; r++) nondominated.add(new CraftPattern<>("T", 1,
                List.of(CraftInput.of("R", r), CraftInput.of("S", 18 - r)), null));
        var fixture = fixture(Map.of("R", 17L, "S", 17L), nondominated, 1);
        assertNotNull(MaterialDagReplay.tryPlan(fixture.graph(), Map.of(nondominated.getFirst(), 1L), "T", 1));
        var budget = new WorkBudget(4096);
        assertNull(TerminalBatchRecovery.tryPlan(fixture.graph(), "T", 1, 4096, budget));
        assertEquals(0, budget.rejected, "a shape refusal is not shared-work exhaustion");
    }

    @Test
    void identityCountBoundsDoNotCountRepeatedRegistrationsOrCollapseEqualSources() {
        var fast = route(2, 1);
        var sixteen = wide(16, List.of(fast));
        var repeated = new ArrayList<>(sixteen);
        for (int i = 0; i < 80; i++) repeated.add(fast);
        assertNull(recover(fixture(Map.of("R", 1L), repeated, 2)),
                "sixteen identities remain outside this new portfolio");

        var sixtyFour = wide(64, List.of(fast));
        for (int i = 0; i < 80; i++) sixtyFour.add(fast);
        var atLimit = fixture(Map.of("R", 1L), sixtyFour, 2);
        assertCertified(atLimit, recover(atLimit), 1);
        var sixtyFive = wide(65, List.of(fast));
        assertNull(recover(fixture(Map.of("R", 1L), sixtyFive, 2)));
    }

    @Test
    void equalSignatureTiesKeepAnOriginalIdentityAndDoNotMutateTheGraph() {
        var first = route(2, 1);
        var equal = route(2, 1);
        var fixture = fixture(Map.of("R", 2L, "T", 1L), wide(17, List.of(first, equal)), 3);
        var before = List.copyOf(fixture.graph().patternsFor("T"));
        var result = recover(fixture);
        assertCertified(fixture, result, 1);
        assertEquals(Map.of(first, 1L), result.firings());
        assertEquals(before, fixture.graph().patternsFor("T"));
        for (int i = 0; i < before.size(); i++) assertSame(before.get(i), fixture.graph().patternsFor("T").get(i));
        fixture.stock().forEach((key, stock) -> assertEquals(stock.longValue(), fixture.graph().stock(key)));
        assertEquals(Map.of("R", 1L, "T", 1L), result.usedStock());
    }

    @Test
    void laterEqualOutputDominatorRemovesTheOldFrontierAndKeepsItsOriginalIdentity() {
        var routes = new ArrayList<CraftPattern<String>>();
        for (int raw = 1; raw <= 16; raw++) routes.add(new CraftPattern<>("T", 2,
                List.of(CraftInput.of("R", raw), CraftInput.of("S", 17 - raw)), new Object()));
        var later = new CraftPattern<>("T", 2,
                List.of(CraftInput.of("R", 1), CraftInput.of("S", 1)), new Object());
        routes.add(later);
        var fixture = fixture(Map.of("R", 1L, "S", 1L), routes, 2);
        var originalOrder = List.copyOf(fixture.graph().patternsFor("T"));

        // The first sixteen routes trade R for S and cannot pay their seventeen inputs.
        // Keeping those entries after the final route dominates them would also leave a
        // seventeen-route frontier, outside the existing small search's sixteen-route bound.
        var result = recover(fixture);
        assertCertified(fixture, result, 1);
        assertEquals(Map.of(later, 1L), result.firings());
        assertSame(later, result.firings().keySet().iterator().next());
        assertEquals(Map.of("R", 1L, "S", 1L), result.usedStock());
        assertEquals(originalOrder, fixture.graph().patternsFor("T"));
    }

    @Test
    void actualWorkRefusalAtSetupSearchAndFinalReplayReturnsNoPartialCertificate() {
        var fixture = fixture(Map.of("R", 2L), wide(17, List.of(route(1, 1))), 2);
        var calls = new ArrayList<Integer>();
        assertCertified(fixture, TerminalBatchRecovery.tryPlan(fixture.graph(), "T", 2, 4096,
                work -> { assertTrue(work > 0); calls.add(work); return true; }), 2);
        assertTrue(calls.size() > 2);
        for (int refusedAt : new int[] {1, calls.size() / 2, calls.size()}) {
            var reached = new AtomicInteger();
            var rejected = new AtomicInteger();
            assertNull(TerminalBatchRecovery.tryPlan(fixture.graph(), "T", 2, 4096, work -> {
                assertTrue(work > 0);
                if (reached.incrementAndGet() == refusedAt) { rejected.incrementAndGet(); return false; }
                assertEquals(0, rejected.get(), "work must stop immediately after a shared refusal");
                return true;
            }));
            assertEquals(refusedAt, reached.get());
            assertEquals(1, rejected.get());
        }

        var rejectedInSmall = new AtomicInteger();
        assertNull(TerminalBatchRecovery.tryPlan(fixture.graph(), "T", 2, 4096, work -> {
            boolean inSmall = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(SmallConservativeSearch.class.getName())));
            if (inSmall) { rejectedInSmall.incrementAndGet(); return false; }
            return true;
        }));
        assertEquals(1, rejectedInSmall.get());
    }

    @Test
    void stateAndDepthCutoffsRemainUnknownWithoutInventedWorkRefusal() {
        var fixture = fixture(Map.of("R", 64L), wide(17, List.of(route(2, 1))), 64);
        assertCertified(fixture, recover(fixture), 32);
        var budget = new WorkBudget(4096);
        assertNull(TerminalBatchRecovery.tryPlan(fixture.graph(), "T", 65, 4096, budget));
        assertEquals(0, budget.rejected, "depth 32 is a bounded miss, not a callback refusal");
        budget = new WorkBudget(4096);
        assertNull(TerminalBatchRecovery.tryPlan(fixture.graph(), "T", 2, 1, budget));
        assertEquals(0, budget.rejected, "state-limit refusal is not shared-work exhaustion");
    }

    @Test
    void callerCancellationAndOptionalDeadlinesPropagateWithoutMutatingStock() {
        var fixture = fixture(Map.of("R", 2L), wide(17, List.of(route(1, 1))), 2);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> recover(fixture));
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> recover(fixture));
        }
        var calls = new AtomicInteger();
        try {
            assertThrows(CancellationException.class, () -> TerminalBatchRecovery.tryPlan(
                    fixture.graph(), "T", 2, 4096, work -> {
                        calls.incrementAndGet();
                        Thread.currentThread().interrupt();
                        return true;
                    }));
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
        assertEquals(2, fixture.graph().stock("R"));
        assertCertified(fixture, recover(fixture), 2);
    }

    private static CraftPattern<String> route(long output, long raw) {
        return new CraftPattern<>("T", output, List.of(CraftInput.of("R", raw)), null);
    }

    private static ArrayList<CraftPattern<String>> wide(int identities, List<CraftPattern<String>> necessary) {
        var routes = new ArrayList<>(necessary);
        while (routes.size() < identities) routes.add(route(1, 256));
        return routes;
    }

    private static Fixture fixture(Map<String, Long> stock, List<CraftPattern<String>> routes, long amount) {
        var builder = CraftGraph.<String>builder();
        stock.forEach(builder::stock);
        routes.forEach(builder::pattern);
        return new Fixture(builder.build(), List.copyOf(routes), Map.copyOf(stock), amount);
    }

    private static CraftPlan<String> recover(Fixture fixture) {
        var budget = new WorkBudget(4096);
        var plan = TerminalBatchRecovery.tryPlan(fixture.graph(), "T", fixture.amount(), 4096, budget);
        assertTrue(budget.used <= 4096);
        return plan;
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> plan, long executions) {
        assertNotNull(plan);
        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertTrue(plan.missing().isEmpty());
        assertTrue(plan.usedReusableStock().isEmpty());
        assertEquals(executions, plan.firings().values().stream().mapToLong(Long::longValue).sum());
        var originals = new IdentityHashMap<CraftPattern<String>, Boolean>();
        fixture.routes().forEach(route -> originals.put(route, Boolean.TRUE));
        plan.firings().keySet().forEach(route -> assertTrue(originals.containsKey(route)));
        var replay = MaterialDagReplay.tryPlan(fixture.graph(), plan.firings(), "T", fixture.amount());
        assertNotNull(replay, "the original graph must certify the returned identities and count vector");
        assertEquals(replay.usedStock(), plan.usedStock());
        fixture.stock().forEach((key, stock) -> assertEquals(stock.longValue(), fixture.graph().stock(key)));
    }

    private static Fixture sample788() {
        int[][] rows = {{11,15,5},{10,6,11},{4,6,7},{10,8,2},{25,13,12},{14,7,8},
                {21,6,3},{20,7,8},{10,10,4},{6,6,7},{16,13,7},{10,10,10},{5,8,2},{9,5,15},
                {20,15,10},{25,6,2},{12,2,7},{20,10,8},{11,10,12},{11,4,12},{14,6,13},
                {24,12,7},{22,3,10},{21,4,10},{3,13,13},{24,13,12},{16,2,9},{6,7,9},
                {5,2,11},{16,1,9},{25,13,11},{18,14,6},{15,3,1},{24,9,11},{24,10,15},
                {6,12,12},{13,7,13},{14,14,6},{21,12,6},{13,10,12},{25,13,1},{2,2,14},
                {25,12,2},{17,1,6},{21,10,10},{19,7,13},{11,2,3},{13,9,10}};
        return terminalRows(rows, Map.of("R", 15L, "S", 21L), 84);
    }

    private static Fixture sample855() {
        int[][] rows = {{15,10,3},{16,8,14},{12,5,9},{16,13,1},{3,11,5},{13,9,3},
                {22,7,2},{15,9,3},{19,9,11},{17,13,15},{18,12,14},{20,9,9},{16,12,8},
                {19,4,14},{6,6,9},{12,3,6},{7,8,1}};
        return terminalRows(rows, Map.of("R", 22L, "S", 25L), 74);
    }

    private static Fixture terminalRows(int[][] rows, Map<String, Long> stock, long amount) {
        var routes = new ArrayList<CraftPattern<String>>();
        for (var row : rows) routes.add(new CraftPattern<>("T", row[0],
                List.of(CraftInput.of("R", row[1]), CraftInput.of("S", row[2])), "p" + routes.size()));
        return fixture(stock, routes, amount);
    }

    private static final class TerminalCheckpoint implements PlanningAttemptContext {
        final CancellationException cancellation;
        boolean enteredRecovery;

        TerminalCheckpoint(CancellationException cancellation) { this.cancellation = cancellation; }
        @Override public long deadlineNanos() { return Long.MAX_VALUE; }
        @Override public void report(PlanningDiagnosticSnapshot snapshot) {}

        @Override public void checkpoint() {
            if (enteredRecovery || !StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(TerminalBatchRecovery.class.getName())))) return;
            enteredRecovery = true;
            if (cancellation != null) throw cancellation;
            // Expire the existing optional 20 ms allowance only after the core witness exists.
            try { Thread.sleep(30); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    private static final class WorkBudget implements IntPredicate {
        final int limit;
        int used;
        int rejected;

        WorkBudget(int limit) { this.limit = limit; }

        @Override public boolean test(int work) {
            assertTrue(work > 0);
            if (work > limit - used) { rejected++; return false; }
            used += work;
            return true;
        }
    }

    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> routes,
            Map<String, Long> stock, long amount) {}
}
