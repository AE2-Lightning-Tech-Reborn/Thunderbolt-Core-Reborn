package com.moakiee.thunderbolt.neoecoqa;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoGtoBatchAdapter;
import com.moakiee.thunderbolt.core.crafting.batch.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Disposable acceptance fixture using the released NeoECO facade, not a replacement API stub. */
@Mod("thunderbolt_neoeco_qa")
@Mod.EventBusSubscriber(modid = "thunderbolt_neoeco_qa")
public final class NeoEcoGtoProtocolProbe {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("thunderbolt_neoeco_qa");
    private static final String ROOT = "cn.dancingsnow.neoecoae.";
    private static boolean done;
    private static int ticks;
    private static int passed;
    private static final StringBuilder REPORT = new StringBuilder();
    private static final AEItemKey INPUT = AEItemKey.of(Items.OAK_LOG);
    private static final AEItemKey OUTPUT = AEItemKey.of(Items.OAK_PLANKS);

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || ++ticks < 40) return;
        done = true;
        try {
            run(event.getServer().overworld());
            java.nio.file.Files.writeString(java.nio.file.Path.of("neoeco-protocol-result.txt"),
                    "PASS cases=" + passed + "\n" + REPORT);
            LOG.info("THUNDERBOLT_NEOECO_QA PASS cases={}", passed);
        } catch (Throwable error) {
            var stack = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(stack));
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("neoeco-protocol-result.txt"),
                    "FAIL\n" + REPORT + stack); }
            catch (java.io.IOException writeFailure) { error.addSuppressed(writeFailure); }
            LOG.error("THUNDERBOLT_NEOECO_QA FAIL", error);
        }
    }

    private static void run(ServerLevel level) throws Exception {
        var adapter = NeoEcoGtoBatchAdapter.createAdapter();
        check(adapter != null, "released GTO API not resolved");
        check(BatchProviderAdapters.entries().stream().anyMatch(entry ->
                        entry.id().toString().equals("thunderbolt:neoeco_gto")),
                "optional adapter was not registered during common setup");
        var f = new Fixture(level, adapter);
        check(adapter.resolve(f.provider) != null, "FastPath provider not resolved");
        check(IBatchCraftingProvider.class.isAssignableFrom(type("blocks.entity.LargeWorkstationPatternProvider")),
                "native workstation bridge missing");
        var nativeProvider = (ICraftingProvider) Proxy.newProxyInstance(
                NeoEcoGtoProtocolProbe.class.getClassLoader(),
                new Class<?>[]{IBatchCraftingProvider.class, type("api.me.provider.ECOFastPathDispatchProvider")},
                (proxy, method, args) -> null);
        check(adapter.resolve(nativeProvider) == null, "native provider was wrapped a second time");
        pass("real API and native workstation capability");

        f.capacity = 3;
        var result = f.run();
        check(result.dispatchedCopies() == 3 && f.remaining == 5 && f.held() == 5,
                "capacity/input conservation");
        check(f.waiting() == 12 && f.accepted == 3 && f.minStock == 5, "pre-extraction admission/output accounting");
        check(f.charged == 3 * f.unitPower && f.dispatches == 1, "energy charged exactly once");
        pass("capacity before extraction, input/output conservation and energy");

        f = new Fixture(level, adapter);
        f.powerCopies = 2;
        result = f.run();
        check(result.dispatchedCopies() == 2 && f.held() == 6 && f.waiting() == 8,
                "power shrink conservation");
        check(f.preparations == 2 && f.accepted == 2 && f.charged == 2 * f.unitPower,
                "smaller final offer was not re-prepared");
        pass("power reduction re-prepares final offer");

        for (String mode : List.of("reject", "throw")) {
            f = new Fixture(level, adapter); f.mode = mode;
            check(f.run().dispatchedCopies() == 0 && f.held() == 8 && f.waiting() == 0,
                    mode + " failed to return CPU inputs");
            check(f.remaining == 8 && f.charged == 0 && f.failure == null && f.ordinary == 0,
                    mode + " replayed or charged rejected work");
            pass(mode + " rolls back without fallback");
        }

        f = new Fixture(level, adapter); f.mode = "ambiguous";
        f.run();
        check("AMBIGUOUS_BATCH_PROVIDER_OWNERSHIP".equals(f.failure) && f.held() == 0,
                "ambiguous ownership was refunded/replayed");
        check(f.dispatches == 1 && f.ordinary == 0 && f.waiting() == 0,
                "ambiguous submission replayed or accounted as success");
        pass("indeterminate ownership stops dispatch without refund or replay");

        f = new Fixture(level, adapter); f.mode = "unavailable";
        check(f.run().dispatchedCopies() == 1 && f.held() == 7 && f.waiting() == 4,
                "ordinary job-aware fallback conservation");
        check(f.ordinary == 1 && f.dispatches == 0 && f.id.equals(f.seenId), "fallback lost UUID");
        pass("unsupported batch uses one job-aware ordinary copy");

        f = new Fixture(level, adapter);
        var endpoint = adapter.resolve(f.provider);
        var template = f.inputs();
        var originalId = f.id;
        var prepared = endpoint.prepareBatch(f.pattern, template, 8, f);
        template[0].reset(); f.id = UUID.randomUUID();
        check(prepared.push(2) == 0 && originalId.equals(f.seenId) && f.accepted == 2,
                "prepared call retained borrowed inputs or job view");
        try { prepared.push(2); throw new AssertionError("admission replay allowed"); }
        catch (IllegalStateException expected) { }
        pass("owned input snapshot, UUID snapshot and single-use admission");

        f = new Fixture(level, adapter); f.capacity = Long.MAX_VALUE;
        var large = adapter.resolve(f.provider).prepareBatch(f.pattern, f.inputs(), Long.MAX_VALUE, f);
        check(large.capacity() == Long.MAX_VALUE / 4, "output arithmetic ceiling lost precision");
        check(large.push(1) == 0 && f.accepted == 1, "large admission could not shrink to one copy");
        pass("Long.MAX_VALUE request respects output ceiling without narrowing");

        f = new Fixture(level, adapter); endpoint = adapter.resolve(f.provider);
        check(endpoint.pushBatch(f.pattern, f.inputs(), 8) == 8 && f.dispatches == 0 && f.ordinary == 0,
                "context-free push created unowned work");
        f.id = null;
        check(endpoint.prepareBatch(f.pattern, f.inputs(), 8, f).capacity() == 0,
                "missing job identity admitted work");
        pass("missing context or UUID transfers no ownership");

        f = new Fixture(level, adapter); f.busy = true;
        check(f.run().dispatchedCopies() == 0 && f.held() == 8 && f.preparations == 0,
                "busy machine extracted inputs");
        f.busy = false;
        check(f.run().dispatchedCopies() == 8 && f.held() == 0 && f.waiting() == 32,
                "cached endpoint did not recover live capacity");
        pass("busy provider recovers through cached endpoint");
    }

    private static final class Fixture implements BatchJobView {
        final ServerLevel level;
        final BatchProviderResolver adapter;
        final IPatternDetails pattern;
        final ICraftingProvider provider;
        final ListCraftingInventory stock = new ListCraftingInventory(key -> observe());
        final ListCraftingInventory waiting = new ListCraftingInventory(key -> {});
        final TickProviderDispatchSchedule schedule = new TickProviderDispatchSchedule();
        final Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batched = new HashMap<>();
        UUID id = UUID.randomUUID();
        UUID seenId;
        String mode = "accept", failure;
        boolean busy;
        long capacity = 8, remaining = 8, accepted, minStock = Long.MAX_VALUE, powerCopies = 8, tick;
        int preparations, dispatches, ordinary;
        double charged;
        final double unitPower;

        Fixture(ServerLevel level, BatchProviderResolver adapter) throws Exception {
            this.level = level; this.adapter = adapter;
            var input = new IPatternDetails.IInput() {
                public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(INPUT, 1)}; }
                public long getMultiplier() { return 1; }
                public boolean isValid(AEKey key, Level ignored) { return INPUT.equals(key); }
                public AEKey getRemainingKey(AEKey key) { return null; }
            };
            pattern = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IMolecularAssemblerSupportedPattern.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getInputs" -> new IPatternDetails.IInput[]{input};
                        case "getOutputs", "getSparseOutputs" -> new GenericStack[]{new GenericStack(OUTPUT, 4)};
                        case "getDefinition" -> AEItemKey.of(Items.PAPER);
                        case "getPrimaryOutput" -> new GenericStack(OUTPUT, 4);
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "NeoECO allocated-input acceptance fixture";
                        case "canSubstitute", "canSubstituteFluids", "supportsPushInputsToExternalInventory" -> false;
                        default -> null;
                    });
            // Explicit capability only for this controlled deterministic fixture; no production recipes change.
            Class<?> registry = type("api.me.pattern.ECOExecutionOptimizationRegistry");
            Class<?> contract = type("api.me.pattern.ECOExecutionOptimizationAdapter");
            Object optimization = type("api.me.pattern.ECOExecutionOptimization").getField("VERIFIED_ASSEMBLER").get(null);
            Object semantic = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{contract},
                    (proxy, method, args) -> method.getName().equals("supports") ? args[0] == pattern : optimization);
            registry.getMethod("register", ResourceLocation.class, int.class, contract).invoke(null,
                    new ResourceLocation("thunderbolt_neoeco_qa", "fixture_" + UUID.randomUUID()), 1000, semantic);
            Class<?> preparation = type("api.me.provider.ECOFastPathDispatchProvider$Preparation");
            Class<?> calculator = type("crafting.execution.fastpath.ECOStatefulBatchCalculator");
            provider = (ICraftingProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{type("api.me.provider.ECOFastPathDispatchProvider"),
                            type("api.me.provider.ECOJobAwareCraftingProvider")}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isBusy": return busy;
                            case "getAvailablePatterns": return List.of(pattern);
                            case "eco$prepareFastPath":
                                preparations++;
                                seenId = (UUID) args[0].getClass().getMethod("craftingJobId").invoke(args[0]);
                                if (mode.equals("unavailable")) return null;
                                return preparation.getConstructor(long.class, calculator, boolean.class, Predicate.class)
                                        .newInstance(capacity, null, false, (Predicate<Object>) this::dispatch);
                            case "pushPattern":
                                check(args.length == 3, "ordinary call lost job-aware API");
                                ordinary++; seenId = (UUID) args[2];
                                check(((KeyCounter[]) args[1])[0].get(INPUT) == 1, "ordinary input not one copy");
                                ((KeyCounter[]) args[1])[0].reset(); accepted++; return true;
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            case "toString": return "Controlled NeoECO GTO provider";
                            default: return null;
                        }
                    });
            unitPower = CraftingCpuHelper.calculatePatternPower(inputs());
            stock.insert(INPUT, 8, Actionable.MODULATE);
        }

        boolean dispatch(Object batch) {
            dispatches++;
            try {
                long count = (long) batch.getClass().getMethod("craftCount").invoke(batch);
                var inputs = (List<GenericStack>) batch.getClass().getMethod("inputTotal").invoke(batch);
                var outputs = (List<GenericStack>) batch.getClass().getMethod("outputTotal").invoke(batch);
                check(inputs.size() == 1 && inputs.getFirst().amount() == count, "released API input totals");
                check(outputs.size() == 1 && outputs.getFirst().amount() == count * 4, "released API output totals");
                if (mode.equals("reject")) return false;
                if (mode.equals("throw")) throw new IllegalArgumentException("explicit rejection before acceptance");
                accepted += count;
                if (mode.equals("ambiguous")) throw (RuntimeException) type("api.me.provider.ECOIndeterminateBatchException")
                        .getConstructor(String.class, Throwable.class).newInstance("acceptance uncertain", null);
                return true;
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }

        KeyCounter[] inputs() { var counter = new KeyCounter(); counter.add(INPUT, 1); return new KeyCounter[]{counter}; }
        long held() { return stock.extract(INPUT, Long.MAX_VALUE, Actionable.SIMULATE); }
        long waiting() { return waiting.extract(OUTPUT, Long.MAX_VALUE, Actionable.SIMULATE); }
        void observe() { if (stock != null) minStock = Math.min(minStock, held()); }

        BatchExecutor.BatchRunResult run() throws ReflectiveOperationException {
            minStock = held();
            schedule.beginTick(++tick);
            var candidates = TickProviderDispatchSchedule.class.getDeclaredMethod("candidates",
                    Object.class, long.class, IPatternDetails.class, java.util.function.Supplier.class);
            candidates.setAccessible(true);
            candidates.invoke(schedule, null, tick, pattern,
                    (java.util.function.Supplier<Iterable<ICraftingProvider>>) () -> List.of(provider));
            batched.clear();
            var energy = (IEnergyService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IEnergyService.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("extractAEPower")) return null;
                        double amount = Math.min((double) args[0], powerCopies * unitPower);
                        if (args[1] == Actionable.MODULATE) charged += amount;
                        return amount;
                    });
            return BatchExecutor.runBatchOnly(10, BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH,
                    null, energy, this, stock, batched, () -> {}, Map.of(), 10, 10_000, false, schedule, adapter);
        }

        public Level level() { return level; }
        public UUID craftingId() { return id; }
        public ListCraftingInventory waitingFor() { return waiting; }
        public void addContainerMaxItems(long count, AEKeyType type) { }
        public void failDispatch(String reason, Throwable cause) { failure = reason; }
        public Iterator<BatchTaskHandle> taskIterator() {
            return new ArrayList<BatchTaskHandle>(List.of(new BatchTaskHandle() {
                public IPatternDetails details() { return pattern; }
                public long getValue() { return remaining; }
                public void setValue(long value) { remaining = value; }
            })).iterator();
        }
    }

    private static Class<?> type(String suffix) throws ClassNotFoundException { return Class.forName(ROOT + suffix); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void pass(String message) {
        passed++; REPORT.append("PASS ").append(message).append('\n');
        LOG.info("THUNDERBOLT_NEOECO_QA case PASS {}", message);
    }
}
