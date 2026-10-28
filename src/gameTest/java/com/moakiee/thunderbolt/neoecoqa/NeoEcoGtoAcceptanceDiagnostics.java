package com.moakiee.thunderbolt.neoecoqa;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;

import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Read-only server-thread snapshots of the upstream physical acceptance fixture. */
@Mod.EventBusSubscriber(modid = "thunderbolt_neoeco_qa")
public final class NeoEcoGtoAcceptanceDiagnostics {
    private static int ticks;

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!Boolean.getBoolean("neoecoae.gtoAcceptance") || event.phase != TickEvent.Phase.END
                || ++ticks % 100 != 0) return;
        try {
            var driver = Class.forName("cn.dancingsnow.neoecoae.client.diagnostics.GTOCompatibilityClientProbe");
            int stage = (int) field(driver, "stage").get(null);
            var pos = (BlockPos) field(driver, "largePos").get(null);
            if (stage < 3 || pos == null) return;
            var level = event.getServer().overworld();
            var large = level.getBlockEntity(pos);
            if (large == null) return;
            var node = (IManagedGridNode) large.getClass().getMethod("getMainNode").invoke(large);
            var state = new StringBuilder("tick=").append(ticks).append(" stage=").append(stage)
                    .append(" clientTicks=").append(field(driver, "ticks").get(null))
                    .append(" received=");
            for (var amount : (KeyCounter) field(driver, "received").get(null))
                state.append(amount.getKey()).append(':').append(amount.getLongValue()).append(',');
            state.append('\n');
            state.append("large=").append(large.saveWithFullMetadata()).append('\n');
            if (node.getGrid() != null) for (var entry : node.getGrid().getCraftingService().getCpus()) {
                if (!(entry instanceof CraftingCPUCluster cpu)) continue;
                var logic = cpu.craftingLogic;
                var items = new KeyCounter();
                logic.getAllItems(items);
                var waiting = new HashSet<AEKey>();
                logic.getAllWaitingFor(waiting);
                state.append("cpu=").append(logic.getClass().getName())
                        .append(" active=").append(cpu.isActive()).append(" budget=").append(cpu.getCoProcessors())
                        .append(" job=").append(logic.hasJob()).append(" inventory=").append(items)
                        .append(" waiting=");
                for (var key : waiting) state.append(key).append(':').append(logic.getWaitingFor(key)).append(',');
                var link = logic.getLastLink();
                state.append(" link=").append(link == null ? null
                        : "done=" + link.isDone() + ",canceled=" + link.isCanceled()).append('\n');
                for (Class<?> type = logic.getClass(); type != null; type = type.getSuperclass()) {
                    for (var member : type.getDeclaredFields()) {
                        if (!appeng.crafting.execution.CraftingCpuLogic.class.isAssignableFrom(member.getType())) continue;
                        member.setAccessible(true);
                        state.append("delegate=").append(member.get(logic).getClass().getName()).append('\n');
                    }
                }
            }
            Files.writeString(Path.of("neoeco-acceptance-diagnostics.txt"), state.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable error) {
            org.slf4j.LoggerFactory.getLogger("thunderbolt_neoeco_qa")
                    .warn("Acceptance diagnostics unavailable", error);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
