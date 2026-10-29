package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;

class ThunderboltCraftingPlanSummaryTest {
    private static final AEKey MATERIAL = new TestKey("material");
    private static final AEKey EMITTED = new TestKey("emitted");
    private static final AEKey PRODUCT = new TestKey("product");

    @Test
    void routesEveryProxyEngineButLeavesAe2VanillaOnItsNativeSummary() {
        assertFalse(ThunderboltCraftingPlanSummary.handles(null));
        assertFalse(ThunderboltCraftingPlanSummary.handles(
                CraftingPlanningEngines.VANILLA_ID));
        assertTrue(ThunderboltCraftingPlanSummary.handles(
                new ResourceLocation("another_mod", "registered_engine")));
        assertTrue(ThunderboltCraftingPlanSummary.handles(
                CraftingPlanningEngines.ALL_FAILED_ID));
    }

    @Test
    void simulationSummaryUsesThePlanningSnapshotSplit() {
        var used = new KeyCounter();
        used.add(MATERIAL, 83_751_862_233L);
        var missing = new KeyCounter();
        missing.add(MATERIAL, 303_668_626_767L);
        var plan = new CraftingPlan(
                null,
                123L,
                true,
                false,
                used,
                new KeyCounter(),
                missing, new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(Map.of()));

        var summary = ThunderboltCraftingPlanSummary.fromPlan(plan);

        assertEquals(123L, summary.getUsedBytes());
        assertTrue(summary.isSimulation());
        assertEquals(1, summary.getEntries().size());
        var entry = summary.getEntries().get(0);
        assertEquals(MATERIAL, entry.getWhat());
        assertEquals(83_751_862_233L, entry.getStoredAmount());
        assertEquals(303_668_626_767L, entry.getMissingAmount());
        assertEquals(0L, entry.getCraftAmount());
    }

    @Test
    void successfulProxyPlanDoesNotInventMissingItems() {
        var used = new KeyCounter();
        used.add(MATERIAL, 10L);
        var plan = new CraftingPlan(
                null,
                1L,
                false,
                false,
                used,
                new KeyCounter(),
                new KeyCounter(), new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(Map.of()));

        var entry = ThunderboltCraftingPlanSummary.fromPlan(plan).getEntries().get(0);

        assertEquals(10L, entry.getStoredAmount());
        assertEquals(0L, entry.getMissingAmount());
    }

    @Test
    void legacyExecutableSummaryDoesNotBecomePreviewOnly() {
        var report = new ExactPlanReport(java.math.BigInteger.TEN, Map.of(), false);
        var executable = new CraftingPlanSummary(10, false, List.of());
        var preview = new CraftingPlanSummary(10, true, List.of());
        ExactPlanReports.attach(executable, report);
        ExactPlanReports.attach(preview, report);

        assertFalse(ExactPlanReports.isPreview(executable));
        assertTrue(ExactPlanReports.isPreview(preview));
        assertEquals(report, ExactPlanReports.get(executable));

        var plan = new CraftingPlan(null, 10, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(),
                new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>());
        ExactPlanReports.attach(plan, report);
        assertTrue(ExactPlanReports.isPreview(plan), "summary compatibility must not weaken submission guards");
    }

    @Test
    void exactReportsPreserveExecutableAndSimulationStatus() {
        for (boolean simulation : List.of(false, true)) {
            for (boolean previewOnly : List.of(false, true)) {
                var plan = new CraftingPlan(null, 1L, simulation, false,
                        new KeyCounter(), new KeyCounter(), new KeyCounter(),
                        new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>());
                var amount = java.math.BigInteger.TEN.pow(30);
                var report = new ExactPlanReport(amount,
                        Map.of(MATERIAL, new ExactPlanReport.Amounts(amount,
                                java.math.BigInteger.ZERO, java.math.BigInteger.ZERO)),
                        false, previewOnly);
                ExactPlanReports.attach(plan, report);

                var summary = ThunderboltCraftingPlanSummary.fromPlan(plan);

                assertEquals(simulation || previewOnly, summary.isSimulation());
                assertEquals(previewOnly, ExactPlanReports.isPreview(summary));
                assertEquals(report, ExactPlanReports.get(summary));
                assertEquals(Long.MAX_VALUE, summary.getUsedBytes());
                assertEquals(Long.MAX_VALUE, entry(summary, MATERIAL).getStoredAmount());
                assertEquals(report.entries().get(MATERIAL),
                        ExactPlanReports.amounts(entry(summary, MATERIAL)));
            }
        }
    }

    @Test
    void preservesEmitterAndPatternCraftingAmounts() {
        var used = new KeyCounter();
        used.add(MATERIAL, 4L);
        var emitted = new KeyCounter();
        emitted.add(EMITTED, 2L);
        var pattern = new FakePattern(PRODUCT, 3L);
        var plan = new CraftingPlan(
                null,
                1L,
                false,
                false,
                used,
                emitted,
                new KeyCounter(), new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(Map.of(pattern, 5L)));

        var summary = ThunderboltCraftingPlanSummary.fromPlan(plan);

        assertEquals(4L, entry(summary, MATERIAL).getStoredAmount());
        assertEquals(2L, entry(summary, EMITTED).getStoredAmount());
        assertEquals(2L, entry(summary, EMITTED).getCraftAmount());
        assertEquals(15L, entry(summary, PRODUCT).getCraftAmount());
    }

    private static CraftingPlanSummaryEntry entry(CraftingPlanSummary summary, AEKey key) {
        return summary.getEntries().stream()
                .filter(entry -> entry.getWhat().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private record FakePattern(AEKey output, long amount) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public GenericStack[] getOutputs() {
            return new GenericStack[] { new GenericStack(output, amount) };
        }
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;

        private TestKey(String id) {
            this.id = id;
        }

        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag() {
            var tag = new CompoundTag();
            tag.putString("id", id);
            return tag;
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() {
            return new ResourceLocation("thunderbolt_test", id);
        }
        @Override public void writeToPacket(FriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(
                long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other && id.equals(other.id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(new ResourceLocation("thunderbolt_test", "summary_key"),
                    TestKey.class, Component.literal("summary key"));
        }

        @Override public AEKey readFromPacket(FriendlyByteBuf input) { return null; }
        @Override public AEKey loadKeyFromTag(CompoundTag tag) { return null; }
    }
}
