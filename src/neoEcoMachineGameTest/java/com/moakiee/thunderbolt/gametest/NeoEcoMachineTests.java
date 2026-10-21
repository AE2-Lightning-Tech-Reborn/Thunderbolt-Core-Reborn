package com.moakiee.thunderbolt.gametest;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import cn.dancingsnow.neoecoae.all.NEBlocks;
import cn.dancingsnow.neoecoae.all.NEMultiBlocks;
import cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity;
import cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingSystemBlockEntity;
import cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingWorkerBlockEntity;
import cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingJobLifecycle;
import cn.dancingsnow.neoecoae.crafting.execution.fastpath.ECOExtractedPatternExecution;
import cn.dancingsnow.neoecoae.impl.storage.infinite.ECOInfiniteStorage;
import cn.dancingsnow.neoecoae.impl.storage.infinite.ECOInfiniteStorageDomains;
import cn.dancingsnow.neoecoae.multiblock.definition.MultiBlockContext;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathCompat;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.concurrent.Future;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;

/** Real source-built mod, real structure calculator, normal grid ticks and persistent storage. */
@GameTestHolder("thunderbolt")
@PrefixGameTestTemplate(false)
public final class NeoEcoMachineTests {
    private static final AEItemKey INPUT = AEItemKey.of(Items.IRON_BLOCK);
    private static final AEItemKey OUTPUT = AEItemKey.of(Items.IRON_INGOT);
    private static final IActionSource SOURCE = IActionSource.empty();

    @GameTest(template = "neoeco_machine", timeoutTicks = 1500)
    public static void activeWorkerSurvivesVanillaNbtLoadBeforeLevelAssignment(GameTestHelper helper) {
        build(helper, false, machine -> {
            UUID cancel = UUID.randomUUID();
            check(helper, NeoEcoFastPathCompat.pushPattern(machine.bus, machine.pattern, inputs(), cancel),
                    "Reload case must begin with owned real work");
            var oldWorker = machine.hosts.get(0).getCluster().getWorkers().get(0);
            var pos = oldWorker.getBlockPos();
            var state = oldWorker.getBlockState();
            var tag = oldWorker.saveWithFullMetadata();
            var decoded = BlockEntity.loadStatic(pos, state, tag);
            check(helper, decoded instanceof ECOCraftingWorkerBlockEntity,
                    "Vanilla NBT load must not discard a worker whose level is not assigned yet");
            var restored = (ECOCraftingWorkerBlockEntity) decoded;
            check(helper, restored.getLevel() == null && restored.getRunningThreads() == 1,
                    "Detached worker must retain its active thread before acquiring a world");
            helper.getLevel().removeBlockEntity(pos);
            helper.getLevel().setBlockEntity(restored);
            restored.onLoad();
            NeoEcoFastPathCompat.cancelJob(helper.getLevel(), cancel);
            helper.runAfterDelay(15, () -> {
                machine.hosts.get(0).rebuildMultiblock();
                restored.reconcileJobTermination();
                helper.startSequence()
                        .thenWaitUntil(() -> {
                            check(helper, machine.amount(INPUT).equals(BigInteger.ONE), "Reloaded cancelled input must recover");
                            check(helper, machine.amount(OUTPUT).signum() == 0, "Reloaded cancelled work must not create output");
                            check(helper, restored.getRunningThreads() == 0, "Restored worker must release custody");
                        })
                        .thenIdle(30)
                        .thenExecute(() -> check(helper, machine.amount(INPUT).equals(BigInteger.ONE),
                                "Reloaded recovery must not duplicate input"))
                        .thenSucceed();
            });
        });
    }

    @GameTest(template = "neoeco_machine", timeoutTicks = 1500)
    public static void ae2CpuPlansExtractsProducesAndCancelsThroughRealMachine(GameTestHelper helper) {
        build(helper, false, machine -> {
            var cpuPos = new BlockPos(2, 1, 1);
            var cpuBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("ae2", "64k_crafting_storage"));
            check(helper, cpuBlock != null, "Real AE2 crafting storage must exist");
            helper.setBlock(cpuPos, cpuBlock.defaultBlockState());
            helper.runAfterDelay(100, () -> {
                var cpu = (AENetworkBlockEntity) helper.getBlockEntity(cpuPos);
                GridHelper.createConnection(cpu.getMainNode().getNode(), machine.bus.getMainNode().getNode());
                helper.runAfterDelay(100, () -> runCpuOrders(helper, machine));
            });
        });
    }

    private static void runCpuOrders(GameTestHelper helper, Machine machine) {
        var grid = machine.bus.getMainNode().getGrid();
        var service = grid.getCraftingService();
        check(helper, !service.getCpus().isEmpty(), "Real AE2 CPU must register in the machine grid");
        var cpu = service.getCpus().stream().filter(CraftingCPUCluster.class::isInstance)
                .map(CraftingCPUCluster.class::cast).findFirst().orElseThrow();
        check(helper, service.isCraftable(OUTPUT), "Real pattern bus must publish its recipe to AE2");
        check(helper, grid.getStorageService().getInventory().insert(INPUT, 3, Actionable.MODULATE, SOURCE) == 3,
                "Seed three real input blocks");
        check(helper, grid.getStorageService().getCachedInventory().get(INPUT) == 3,
                "AE2 planning snapshot must see all three input blocks");
        check(helper, grid.getStorageService().getInventory().extract(INPUT, 3, Actionable.SIMULATE, SOURCE) == 3,
                "Seeded input must be extractable through the real network");
        ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {
            public IActionSource getActionSource() { return SOURCE; }
            public IGridNode getGridNode() { return cpu.getNode(); }
        };
        Future<ICraftingPlan> plan = service.beginCraftingCalculation(helper.getLevel(), requester,
                OUTPUT, 27, CalculationStrategy.REPORT_MISSING_ITEMS);
        var link = new ICraftingLink[1];
        var nextPlan = new ArrayList<Future<ICraftingPlan>>();
        var cancelId = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(() -> check(helper, plan.isDone(), "AE2 planning must finish"))
                .thenExecute(() -> {
                    check(helper, !completed(plan).simulation(),
                            "Real stocked plan must be executable; missing=" + describe(completed(plan).missingItems())
                            + "; used=" + describe(completed(plan).usedItems())
                            + "; patterns=" + completed(plan).patternTimes());
                    ICraftingSubmitResult submitted = service.submitJob(completed(plan), null, cpu, true, SOURCE);
                    check(helper, submitted.successful(), "AE2 must accept its real plan: " + submitted.errorCode());
                    // With no requesting machine AE2 deliberately returns successful(null).
                    link[0] = cpu.craftingLogic.getLastLink();
                    check(helper, link[0] != null, "Submitted CPU must own its real crafting link");
                })
                .thenWaitUntil(() -> {
                    // Standalone AE2 links have no nexus, so isDone() remains false even on success.
                    check(helper, link[0] != null && !cpu.isBusy()
                                    && Boolean.TRUE.equals(ECOCraftingJobLifecycle.completion(
                                            helper.getLevel(), link[0].getCraftingID())),
                            "AE2 CPU must finish after real machine output delivery; busy=" + cpu.isBusy()
                            + "; cancelled=" + (link[0] != null && link[0].isCanceled())
                            + "; stock=" + machine.amount(INPUT) + "/" + machine.amount(OUTPUT)
                            + "; cpuStock=" + cpu.craftingLogic.getStored(INPUT)
                            + "; waiting=" + cpu.craftingLogic.getWaitingFor(OUTPUT)
                            + "; idle=" + machine.idle());
                    check(helper, machine.amount(INPUT).signum() == 0, "AE2 must consume exactly three input blocks");
                    check(helper, machine.amount(OUTPUT).equals(BigInteger.valueOf(27)), "AE2 must deliver exactly 27 ingots");
                    check(helper, machine.idle(), "Completed CPU work must release workers");
                })
                .thenExecute(() -> {
                    check(helper, grid.getStorageService().getInventory().insert(INPUT, 100, Actionable.MODULATE, SOURCE) == 100,
                            "Seed real input for cancellation");
                    nextPlan.add(service.beginCraftingCalculation(helper.getLevel(), requester,
                            OUTPUT, 900, CalculationStrategy.REPORT_MISSING_ITEMS));
                })
                .thenWaitUntil(() -> check(helper, nextPlan.get(0).isDone(), "Cancellation plan must finish"))
                .thenExecute(() -> {
                    var submitted = service.submitJob(completed(nextPlan.get(0)), null, cpu, true, SOURCE);
                    check(helper, submitted.successful(), "AE2 must accept the cancellable order: " + submitted.errorCode());
                    link[0] = cpu.craftingLogic.getLastLink();
                    check(helper, link[0] != null, "Cancellable CPU must own its real crafting link");
                    cancelId[0] = link[0].getCraftingID();
                })
                .thenWaitUntil(() -> check(helper, !machine.idle(), "Cancel only after a real worker owns inputs"))
                .thenExecute(() -> {
                    cpu.cancelJob();
                    check(helper, link[0].isCanceled(), "AE2 CPU cancellation must terminate the link");
                })
                .thenWaitUntil(() -> {
                    check(helper, machine.idle(), "AE2 cancellation must drain all worker custody");
                    check(helper, Boolean.FALSE.equals(ECOCraftingJobLifecycle.completion(helper.getLevel(), cancelId[0])),
                            "CPU cancellation must notify real NeoECO lifecycle");
                    checkConservation(helper, machine);
                })
                .thenIdle(30)
                .thenExecute(() -> checkConservation(helper, machine))
                .thenSucceed();
    }

    private static ICraftingPlan completed(Future<ICraftingPlan> future) {
        try { return future.get(); }
        catch (Exception failure) { throw new IllegalStateException("Real AE2 calculation failed", failure); }
    }

    private static String describe(KeyCounter counter) {
        var entries = new ArrayList<String>();
        for (var entry : counter) entries.add(entry.getKey() + "=" + entry.getLongValue());
        return entries.toString();
    }

    private static void checkConservation(GameTestHelper helper, Machine machine) {
        BigInteger ingots = machine.amount(OUTPUT).subtract(BigInteger.valueOf(27));
        check(helper, ingots.signum() >= 0 && ingots.mod(BigInteger.valueOf(9)).signum() == 0,
                "Cancelled order must retain only complete recipe outputs");
        check(helper, machine.amount(INPUT).add(ingots.divide(BigInteger.valueOf(9))).equals(BigInteger.valueOf(100)),
                "CPU cancellation must conserve every input as either refunded blocks or completed ingots");
    }

    @GameTest(template = "neoeco_machine", timeoutTicks = 1500)
    public static void formedMachineProducesBatchesAndRecoversCancelledInputs(GameTestHelper helper) {
        build(helper, false, machine -> {
            UUID warmup = UUID.randomUUID();
            helper.assertTrue(machine.bus.pushPattern(ECOExtractedPatternExecution.fromProviderPush(
                    machine.pattern, inputs(), helper.getLevel()), warmup),
                    "Formed bus must accept ordinary warmup");
            machine.bus.releaseJobOutputsToNetwork(warmup);
            helper.startSequence()
                    .thenWaitUntil(() -> check(helper, machine.amount(OUTPUT).equals(BigInteger.valueOf(9)),
                            "Warmup output must reach real storage"))
                    .thenExecute(() -> {
                        var endpoint = ((BatchProviderResolver) NeoEcoFastPathCompat.createAdapter()).resolve(machine.bus);
                        check(helper, endpoint != null, "Real bus adapter must resolve");
                        UUID job = UUID.randomUUID();
                        var template = inputs();
                        var prepared = endpoint.prepareBatch(machine.pattern, template, 7, job(helper, job));
                        check(helper, prepared.capacity() >= 7, "Verified real machine must admit seven copies");
                        check(helper, prepared.push(7) == 0, "Real finite batch must accept all seven copies");
                        check(helper, template[0].get(INPUT) == 1, "Adapter must preserve borrowed input template");
                        machine.bus.releaseJobOutputsToNetwork(job);
                    })
                    .thenWaitUntil(() -> check(helper, machine.amount(OUTPUT).equals(BigInteger.valueOf(72)),
                            "Warmup plus finite batch must produce exactly 72 ingots"))
                    .thenExecute(() -> {
                        UUID cancel = UUID.randomUUID();
                        check(helper, NeoEcoFastPathCompat.pushPattern(machine.bus, machine.pattern, inputs(), cancel),
                                "Cancellation case must own real accepted work");
                        check(helper, machine.bus.getCluster().getWorkers().stream()
                                .anyMatch(w -> w.getRunningThreads() > 0), "Worker must be busy before cancellation");
                        NeoEcoFastPathCompat.cancelJob(helper.getLevel(), cancel);
                        check(helper, Boolean.FALSE.equals(ECOCraftingJobLifecycle.completion(helper.getLevel(), cancel)),
                                "Native cancellation must publish a durable terminal decision");
                    })
                    .thenWaitUntil(() -> {
                        check(helper, machine.amount(INPUT).equals(BigInteger.ONE), "Cancelled input must return to storage");
                        check(helper, machine.amount(OUTPUT).equals(BigInteger.valueOf(72)), "Cancelled work must not create outputs");
                        check(helper, machine.idle(), "Cancelled worker and controller counts must return to idle");
                    })
                    .thenIdle(30)
                    .thenExecute(() -> {
                        check(helper, machine.amount(INPUT).equals(BigInteger.ONE), "Recovery retries must not duplicate input");
                        check(helper, machine.amount(OUTPUT).equals(BigInteger.valueOf(72)), "No late cancelled output is allowed");
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = "neoeco_machine", timeoutTicks = 1500)
    public static void eightFullHostsExecuteAndCancelExactBatchesBeyondLong(GameTestHelper helper) {
        build(helper, true, machine -> {
            check(helper, machine.hosts.stream().allMatch(ECOCraftingSystemBlockEntity::isFullVirtualCraftingMode),
                    "All eight maximum-length L9 hosts must qualify for virtual crafting naturally");
            UUID warmup = UUID.randomUUID();
            check(helper, machine.bus.pushPattern(ECOExtractedPatternExecution.fromProviderPush(
                    machine.pattern, inputs(), helper.getLevel()), warmup),
                    "Virtual network must accept real recipe verification warmup");
            machine.bus.releaseJobOutputsToNetwork(warmup);
            BigInteger copies = BigInteger.ONE.shiftLeft(70).add(BigInteger.valueOf(37));
            BigInteger expectedOutput = copies.multiply(BigInteger.valueOf(9)).add(BigInteger.valueOf(9));
            helper.startSequence()
                    .thenWaitUntil(() -> check(helper, machine.amount(OUTPUT).equals(BigInteger.valueOf(9)),
                            "Virtual network warmup must complete"))
                    .thenExecute(() -> {
                        UUID job = UUID.randomUUID();
                        var template = inputs();
                        var exact = NeoEcoFastPathCompat.prepareExact(machine.bus, machine.pattern, template,
                                copies, helper.getLevel(), job);
                        check(helper, exact != null && exact.craftCount().equals(copies),
                                "Real bus must admit the entire BigInteger count");
                        check(helper, machine.idle(), "Exact preparation must not dispatch work");
                        check(helper, exact.submit(), "Real native exact dispatch must accept");
                        check(helper, template[0].get(INPUT) == 1, "Exact dispatch must preserve borrowed input template");
                        machine.bus.releaseJobOutputsToNetwork(job);
                    })
                    .thenWaitUntil(() -> {
                        check(helper, machine.amount(OUTPUT).equals(expectedOutput), "Exact output must reach persistent storage without truncation");
                        check(helper, machine.idle(), "Exact output delivery must release all worker counts");
                    })
                    .thenExecute(() -> {
                        UUID cancel = UUID.randomUUID();
                        var exact = NeoEcoFastPathCompat.prepareExact(machine.bus, machine.pattern, inputs(),
                                copies, helper.getLevel(), cancel);
                        check(helper, exact != null && exact.submit(), "Exact cancellation must start accepted real work");
                        NeoEcoFastPathCompat.cancelJob(helper.getLevel(), cancel);
                        check(helper, Boolean.FALSE.equals(ECOCraftingJobLifecycle.completion(helper.getLevel(), cancel)),
                                "Exact cancellation must use native lifecycle");
                    })
                    .thenWaitUntil(() -> {
                        check(helper, machine.amount(INPUT).equals(copies), "Exact cancellation must recover the full BigInteger input");
                        check(helper, machine.amount(OUTPUT).equals(expectedOutput), "Cancelled exact work must not create outputs");
                        check(helper, machine.idle(), "Exact recovery must release all worker counts");
                    })
                    .thenIdle(30)
                    .thenExecute(() -> {
                        check(helper, machine.amount(INPUT).equals(copies), "Exact recovery retries must not duplicate stock");
                        check(helper, machine.amount(OUTPUT).equals(expectedOutput), "Exact completed output must remain stable");
                    })
                    .thenSucceed();
        });
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    private static KeyCounter[] inputs() {
        var counter = new KeyCounter();
        counter.add(INPUT, 1);
        return new KeyCounter[] {counter};
    }

    private static BatchJobView job(GameTestHelper helper, UUID id) {
        return (BatchJobView) Proxy.newProxyInstance(BatchJobView.class.getClassLoader(),
                new Class<?>[] {BatchJobView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "level" -> helper.getLevel();
                    case "craftingId" -> id;
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static void build(GameTestHelper helper, boolean virtual, Consumer<Machine> ready) {
        var hosts = new ArrayList<ECOCraftingSystemBlockEntity>();
        int hostCount = virtual ? 8 : 1;
        int length = virtual ? NEMultiBlocks.CRAFTING_SYSTEM_L9.getExpandMax() : 1;
        var definition = virtual ? NEMultiBlocks.CRAFTING_SYSTEM_L9 : NEMultiBlocks.CRAFTING_SYSTEM_L4;
        BlockPos busPos = new BlockPos(length + 2, 1, 3);
        for (int host = 0; host < hostCount; host++) {
            BlockPos offset = new BlockPos(length + 3, 1, 2 + host * 4);
            definition.createLevel(new MultiBlockContext() {
                { repeats = length; }
                public void setBlock(BlockPos pos, BlockState state) { helper.setBlock(offset.offset(pos), state); }
                public void setBlockEntity(BlockPos pos, BiFunction<BlockPos, BlockState, BlockEntity> factory) {
                    throw new AssertionError("Definition must place real registered block entities");
                }
                public Level getLevel() { return helper.getLevel(); }
                public List<BlockPos> allBlocks() { return List.of(); }
                public boolean isFormed() { return false; }
            });
            if (virtual) helper.setBlock(offset.offset(1, 1, 0), NEBlocks.CRAFTING_SYSTEM_L9.getDefaultState());
            if (virtual) helper.setBlock(offset.offset(2, 1, 0), NEBlocks.CRAFTING_HIGH_ENERGY_NETWORK_SWITCH.getDefaultState());
            hosts.add((ECOCraftingSystemBlockEntity) helper.getBlockEntity(offset.offset(1, 1, 0)));
        }
        var powerPos = new BlockPos(1, 1, 1);
        var power = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("ae2", "creative_energy_cell"));
        check(helper, power != null, "Real AE2 power block must exist");
        helper.setBlock(powerPos, power.defaultBlockState());
        helper.runAfterDelay(15, () -> {
            var powerEntity = (AENetworkBlockEntity) helper.getBlockEntity(powerPos);
            for (var host : hosts) {
                host.rebuildMultiblock();
                check(helper, host.isFormed(), "Real structure calculator must form every host");
                if (virtual) host.setNetworkFrequency(123);
                GridHelper.createConnection(powerEntity.getMainNode().getNode(), host.getMainNode().getNode());
            }
            var bus = (ECOCraftingPatternBusBlockEntity) helper.getBlockEntity(busPos);
            var recipe = (CraftingRecipe) helper.getLevel().getRecipeManager()
                    .byKey(new ResourceLocation("minecraft", "iron_ingot_from_iron_block")).orElseThrow();
            var slots = new ItemStack[9];
            Arrays.fill(slots, ItemStack.EMPTY);
            slots[0] = new ItemStack(Items.IRON_BLOCK);
            var encoded = PatternDetailsHelper.encodeCraftingPattern(recipe, slots,
                    new ItemStack(Items.IRON_INGOT, 9), false, false);
            var pattern = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
            bus.itemHandler.setStackInSlot(0, encoded);
            var storage = new ECOInfiniteStorage(ECOInfiniteStorageDomains.create(helper.getLevel(), UUID.randomUUID()),
                    Component.literal("Machine acceptance storage"));
            IGrid grid = hosts.get(0).getMainNode().getGrid();
            grid.getStorageService().addGlobalStorageProvider(mounts -> mounts.mount(storage, 0));
            var machine = new Machine(hosts, bus, pattern, storage);
            helper.runAfterDelay(100, () -> {
                check(helper, hosts.stream().allMatch(h -> h.getMainNode().isActive()), "Machine grid must be powered and have channels");
                check(helper, !bus.isBusy(), "Formed powered machine must have a free lane");
                ready.accept(machine);
            });
        });
    }

    private record Machine(List<ECOCraftingSystemBlockEntity> hosts, ECOCraftingPatternBusBlockEntity bus,
            IPatternDetails pattern, ECOInfiniteStorage storage) {
        BigInteger amount(AEItemKey key) { return storage.getExactAmount(key).toBigInteger(); }
        boolean idle() { return hosts.stream().allMatch(h -> h.getRunningThreadCount() == 0)
                && hosts.stream().flatMap(h -> h.getCluster().getWorkers().stream())
                        .allMatch(w -> w.getRunningThreads() == 0); }
    }
}
