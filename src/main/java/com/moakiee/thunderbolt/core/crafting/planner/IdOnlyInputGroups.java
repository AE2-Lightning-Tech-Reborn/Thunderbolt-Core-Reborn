package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Calculation-local same-id demand. Each concrete member supplies whole input units through a
 * zero-cost edge. There is no copied group stock: strict and fuzzy consumers still draw from the
 * same physical resource. A real recipe therefore needs one group input, not a Cartesian product
 * of component variants or integer allocations of a large slot multiplier.
 */
final class IdOnlyInputGroups {
    private final Map<DemandKey, Group> groups = new HashMap<>();

    AEKey register(List<CraftInput<AEKey>> options, CraftGraph.Builder<AEKey> builder,
            Consumer<AEKey> discover, Runnable chargeWork) {
        CraftInput<AEKey> first = options.getFirst();
        var proposed = new DemandKey(LateBoundOutputKey.physical(first.key()), first.amount());
        for (var option : options) {
            if (option.returned() || option.remainder() != null || option.reusableStockSource() != null
                    || option.amount() != first.amount()
                    || !option.exactAmount().equals(first.exactAmount())
                    || !LateBoundOutputKey.physical(option.key()).dropSecondary().equals(proposed.identity)) {
                return null;
            }
        }
        Group group = groups.computeIfAbsent(proposed, key -> new Group(key, new LinkedHashSet<>()));
        for (var option : options) {
            chargeWork.run();
            if (group.members.add(option.key())) {
                builder.pattern(CraftPattern.tagConversion(
                        option.key(), group.key.unitAmount, group.key, group.key));
                discover.accept(option.key());
            }
        }
        return group.key;
    }

    static boolean isGroup(AEKey key) {
        return key instanceof DemandKey;
    }

    /** Remove logical transfers before CPU tasks, byte accounting, or AE2 serialization. */
    static CraftPlan<AEKey> project(CraftPlan<AEKey> plan) {
        Map<AEKey, Long> missing = new LinkedHashMap<>();
        plan.missing().forEach((key, amount) -> {
            if (key instanceof DemandKey group) {
                missing.merge(group.representative, Sat.mul(amount, group.unitAmount), Sat::add);
            } else missing.merge(key, amount, Sat::add);
        });
        return new CraftPlan<>(plan.supported(), plan.feasible(), realFirings(plan.firings()),
                plan.usedStock(), plan.usedReusableStock(), missing, physicalDemand(plan.grossDemand()),
                plan.itemsProcessed(), plan.budgetExhausted());
    }

    static ExactCraftPlan<AEKey> project(ExactCraftPlan<AEKey> plan) {
        Map<AEKey, BigInteger> missing = new LinkedHashMap<>();
        plan.missing().forEach((key, amount) -> {
            if (key instanceof DemandKey group) {
                missing.merge(group.representative, amount.multiply(BigInteger.valueOf(group.unitAmount)),
                        BigInteger::add);
            } else missing.merge(key, amount, BigInteger::add);
        });
        return new ExactCraftPlan<>(realFirings(plan.firings()), plan.usedStock(), missing,
                physicalDemand(plan.grossDemand()), plan.incomplete());
    }

    private static <N> Map<CraftPattern<AEKey>, N> realFirings(Map<CraftPattern<AEKey>, N> firings) {
        Map<CraftPattern<AEKey>, N> result = new LinkedHashMap<>();
        firings.forEach((pattern, amount) -> {
            if (!(pattern.source() instanceof DemandKey)) result.put(pattern, amount);
        });
        return result;
    }

    private static <N> Map<AEKey, N> physicalDemand(Map<AEKey, N> demand) {
        Map<AEKey, N> result = new LinkedHashMap<>();
        demand.forEach((key, amount) -> {
            // Member inputs already record the physical demand, including the template unit size.
            if (!isGroup(key)) result.put(key, amount);
        });
        return result;
    }

    private record Group(DemandKey key, Set<AEKey> members) {}

    private static final class DemandKey extends AEKey {
        private final AEKey representative;
        private final AEKey identity;
        private final long unitAmount;

        private DemandKey(AEKey representative, long unitAmount) {
            this.representative = representative;
            this.identity = representative.dropSecondary();
            this.unitAmount = unitAmount;
        }

        @Override public AEKeyType getType() { return representative.getType(); }
        @Override public AEKey dropSecondary() { return this; }
        @Override public Object getPrimaryKey() { return representative.getPrimaryKey(); }
        @Override public ResourceLocation getId() { return representative.getId(); }
        @Override public boolean hasComponents() { return false; }
        @Override protected Component computeDisplayName() { return representative.getDisplayName(); }
        @Override public CompoundTag toTag(HolderLookup.Provider registries) {
            throw new IllegalStateException("planner-only input group escaped into serialization");
        }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) {
            throw new IllegalStateException("planner-only input group escaped into a packet");
        }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
            throw new IllegalStateException("planner-only input group escaped into the world");
        }
        @Override public boolean equals(Object other) {
            return other instanceof DemandKey group
                    && unitAmount == group.unitAmount && identity.equals(group.identity);
        }
        @Override public int hashCode() { return 31 * identity.hashCode() + Long.hashCode(unitAmount); }
        @Override public String toString() { return "id-only(" + identity + ", unit=" + unitAmount + ")"; }
    }
}
