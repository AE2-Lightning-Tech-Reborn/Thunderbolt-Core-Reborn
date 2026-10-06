package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Metadata must follow each immutable recipe snapshot, including newly projected recipes. */
class HostFeedbackMetadataTest {
    private static final long REQUEST = 1_000L;

    @Test
    void addingAHostSeedToTheBuilderDoesNotChangeAnEarlierSnapshot() {
        var fixture = new ReverseConverterFixture();
        var builder = fixture.builderWithoutGain(1);
        var ordinarySnapshot = builder.build();
        assertNoGainPlan(ordinarySnapshot);

        var feedbackSnapshot = builder.pattern(fixture.gain).build();
        assertBootstrapPlan(feedbackSnapshot, fixture);

        assertNoGainPlan(ordinarySnapshot);
        assertTrue(ordinarySnapshot.patternsFor("crystal").isEmpty());
        assertSame(fixture.gain, feedbackSnapshot.patternsFor("crystal").get(0));
    }

    @Test
    void recipeProjectionCanIntroduceAndThenRemoveAReverseConverterBootstrap() {
        var fixture = new ReverseConverterFixture();
        var ordinary = fixture.builderWithoutGain(1).build();
        var projected = ordinary.withPatterns(fixture.withGain());
        assertBootstrapPlan(projected, fixture);

        var removed = projected.withPatterns(fixture.withoutGain());
        assertNoGainPlan(removed);
        assertNoGainPlan(ordinary);
        assertBootstrapPlan(projected, fixture);
    }

    @Test
    void stockProjectionsKeepTheHostSeedRouteButCannotInventStartupStock() {
        var fixture = new ReverseConverterFixture();
        var empty = fixture.builderWithoutGain(0).pattern(fixture.gain).build();
        assertTrue(empty.hasHostFeedbackSeeds());
        assertFalse(CraftPlannerV2.plan(empty, "charged_crystal", REQUEST).feasible());

        var added = empty.withAdditionalStock(Map.of("crystal", 1L));
        assertBootstrapPlan(added, fixture);

        var stocked = fixture.builderWithoutGain(2).pattern(fixture.gain).build();
        assertBootstrapPlan(stocked.withStockLimits(Map.of("crystal", 1L)), fixture);
        assertBootstrapPlan(stocked.withoutStock(Map.of("crystal", 1L)), fixture);

        var depleted = added.withoutStock(Map.of("crystal", 1L));
        assertTrue(depleted.hasHostFeedbackSeeds());
        assertFalse(CraftPlannerV2.plan(depleted, "charged_crystal", REQUEST).feasible(),
                "the recipe metadata must not turn its missing physical bootstrap into supply");
        assertBootstrapPlan(added, fixture);
    }

    private static void assertNoGainPlan(CraftGraph<String> graph) {
        assertFalse(graph.hasHostFeedbackSeeds(),
                "a registered private route alone is not a host-backed recipe input");
        assertFalse(CraftPlannerV2.plan(graph, "charged_crystal", REQUEST).feasible());
    }

    private static void assertBootstrapPlan(CraftGraph<String> graph, ReverseConverterFixture fixture) {
        assertTrue(graph.hasHostFeedbackSeeds());
        var plan = CraftPlannerV2.plan(graph, "charged_crystal", REQUEST);
        assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
        assertTrue(plan.missing().isEmpty());
        assertEquals(1L, plan.usedStock().getOrDefault("crystal", 0L));
        assertTrue(plan.usedReusableStock().isEmpty(),
                "there is no preexisting host seed; the converter must manufacture it");
        assertEquals(1L, plan.firings().getOrDefault(fixture.converter, 0L));
        assertEquals(REQUEST, plan.firings().getOrDefault(fixture.gain, 0L));
        assertEquals(REQUEST, plan.firings().getOrDefault(fixture.charge, 0L));
        assertEquals(3, plan.firings().size(), "the certificate must retain the original recipe identities");
    }

    /** The downstream request meets the ordinary converter as a DFS back edge. */
    private static final class ReverseConverterFixture {
        private final ReusableStockSource source = new ReusableStockSource("host", "certus_loop");
        private final CraftPattern<String> converter = new CraftPattern<>(
                "dust", 1, List.of(CraftInput.of("crystal", 1)), "crush_certus");
        private final CraftPattern<String> gain = new CraftPattern<>(
                "crystal", 1, List.of(CraftInput.returnedFrom("dust", 1, source)), "contracted_gain");
        private final CraftPattern<String> charge = new CraftPattern<>(
                "charged_crystal", 1, List.of(CraftInput.of("crystal", 1)), "charge_certus");

        private CraftGraph.Builder<String> builderWithoutGain(long crystalStock) {
            return CraftGraph.<String>builder()
                    .pattern(converter)
                    .pattern(charge)
                    .stock("crystal", crystalStock)
                    .reusableStockRoute(source, "dust", List.of("dust"));
        }

        private Map<String, List<CraftPattern<String>>> withoutGain() {
            return Map.of("dust", List.of(converter), "charged_crystal", List.of(charge));
        }

        private Map<String, List<CraftPattern<String>>> withGain() {
            return Map.of("dust", List.of(converter), "crystal", List.of(gain),
                    "charged_crystal", List.of(charge));
        }
    }
}
