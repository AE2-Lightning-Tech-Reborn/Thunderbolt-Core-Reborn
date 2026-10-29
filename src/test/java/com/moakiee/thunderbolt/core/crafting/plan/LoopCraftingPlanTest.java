package com.moakiee.thunderbolt.core.crafting.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.moakiee.thunderbolt.api.crafting.cpu.ExtendedCraftingCpuCluster;
import com.moakiee.thunderbolt.api.crafting.cpu.ExtendedCraftingCpuClusterHost;
import com.moakiee.thunderbolt.core.crafting.loop.CraftingCpuRestrictedPattern;
import com.moakiee.thunderbolt.core.crafting.loop.ReusableSeedPattern;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class LoopCraftingPlanTest {

    @Test
    void exactReportSurvivesLoopWrappingAndSummaryConversion() {
        var seed = new TestKey("seed");
        var pattern = new TestLoopPattern(seed, new TestHost());
        for (boolean previewOnly : List.of(false, true)) {
            var delegate = new CraftingPlan(new GenericStack(seed, 1L), 0L, false, false,
                    new KeyCounter(), new KeyCounter(), new KeyCounter(),
                    new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(Map.of(pattern, 1L)));
            var amount = java.math.BigInteger.TEN.pow(30);
            var report = new com.moakiee.thunderbolt.ae2.crafting.ExactPlanReport(amount,
                    Map.of(seed, new com.moakiee.thunderbolt.ae2.crafting.ExactPlanReport.Amounts(
                            amount, java.math.BigInteger.ZERO, amount)), false, previewOnly);
            com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.attach(delegate, report);

            var wrapped = LoopCraftingPlan.wrapIfNeeded(delegate);
            assertSame(report, com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.get(wrapped));
            assertEquals(previewOnly,
                    com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.isPreview(wrapped));
            var summary = com.moakiee.thunderbolt.ae2.crafting.ThunderboltCraftingPlanSummary.fromPlan(wrapped);
            assertSame(report, com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.get(summary));
            assertEquals(Long.MAX_VALUE, summary.getUsedBytes());
            assertEquals(previewOnly, summary.isSimulation());
        }
    }

    @Test
    void wrapsCoreLoopMetadataIntoCorePlanImplementation() {
        var acceptedHost = new TestHost();
        var otherHost = new TestHost();
        var seed = new TestKey("seed");
        var pattern = new TestLoopPattern(seed, acceptedHost);
        var delegate = new CraftingPlan(
                new GenericStack(seed, 1L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(Map.of(pattern, 1L)));

        var wrapped = (LoopCraftingPlan) LoopCraftingPlan.wrapIfNeeded(delegate);

        assertSame(delegate, wrapped.delegate());
        assertEquals(Map.of(seed, 1L), wrapped.totalReusableSeeds());
        assertTrue(wrapped.canRunOn(acceptedHost));
        assertFalse(wrapped.canRunOn(otherHost));
        assertFalse(LoopCraftingPlan.class.getPackageName().contains(".api."));
    }

    private static final class TestHost implements ExtendedCraftingCpuClusterHost {
        @Override
        public ExtendedCraftingCpuCluster getExtendedCraftingCpuCluster() {
            return null;
        }
    }

    private static final class TestLoopPattern
            implements IPatternDetails, CraftingCpuRestrictedPattern, ReusableSeedPattern {
        private final AEKey seed;
        private final ExtendedCraftingCpuClusterHost acceptedHost;
        private final UUID groupId = UUID.randomUUID();

        private TestLoopPattern(AEKey seed, ExtendedCraftingCpuClusterHost acceptedHost) {
            this.seed = seed;
            this.acceptedHost = acceptedHost;
        }

        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public GenericStack[] getOutputs() {
            return new GenericStack[] {new GenericStack(seed, 1L)};
        }
        @Override public boolean acceptsCraftingCpu(ExtendedCraftingCpuClusterHost host) {
            return host == acceptedHost;
        }
        @Override public Object reusableSeedStorageScope() { return "host"; }
        @Override public UUID reusableSeedGroupId() { return groupId; }
        @Override public Set<AEKey> reusableSeedCycleKeys() { return Set.of(seed); }
        @Override public boolean hasSingleSeedInputPerMember() { return true; }
        @Override public Map<AEKey, Long> totalReusableSeedRequirements() {
            return Map.of(seed, 1L);
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
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other && id.equals(other.id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(new ResourceLocation("thunderbolt_test", "loop_key"),
                    TestKey.class, Component.literal("loop key"));
        }

        @Override public AEKey readFromPacket(FriendlyByteBuf input) { return null; }
        @Override public AEKey loadKeyFromTag(CompoundTag tag) { return null; }
    }
}
