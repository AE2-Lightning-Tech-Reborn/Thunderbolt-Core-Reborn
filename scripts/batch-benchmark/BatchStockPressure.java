package com.moakiee.thunderbolt.core.crafting.batch;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.google.gson.GsonBuilder;
import com.moakiee.thunderbolt.core.crafting.pattern.PlannedInputPattern;
import com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchMode;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKeyType;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.level.Level;

/** Standalone randomized inventory oracle; compile with current main/test runtime classes. */
public final class BatchStockPressure {
    private record Pattern(IInput[] inputs) implements IPatternDetails {
        public AEItemKey getDefinition() { return null; }
        public IInput[] getInputs() { return inputs; }
        public GenericStack[] getOutputs() { return new GenericStack[0]; }
    }

    private record Input(AEKey a, AEKey b, long unit, long multiplier) implements IPatternDetails.IInput {
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[] {new GenericStack(b, unit), new GenericStack(a, unit)};
        }
        public long getMultiplier() { return multiplier; }
        public boolean isValid(AEKey key, Level level) { return key.equals(a) || key.equals(b); }
        public AEKey getRemainingKey(AEKey key) { return null; }
    }

    private static long held(ListCraftingInventory inventory, AEKey key) {
        return inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    private static void equal(long expected, long actual, String where) {
        if (expected != actual) throw new AssertionError(where + ": " + expected + " != " + actual);
    }

    private static void providerCase(Random random, AEKey[] keys) {
        long scale = random.nextInt(7) == 0 ? 1_000_000_000_000L : 1L;
        long demand = 1 + random.nextInt(4), request = (1 + random.nextInt(100)) * scale;
        long initial = (1 + random.nextInt(120)) * scale * demand;
        long reserve = random.nextInt(30) * scale * demand, maximum = (1 + random.nextInt(100)) * scale;
        var source = new IPatternDetails() {
            public AEItemKey getDefinition() { return null; }
            public IInput[] getInputs() { return new IInput[] {new Input(keys[0], keys[1], 1, demand)}; }
            public GenericStack[] getOutputs() { return new GenericStack[] {new GenericStack(keys[1], 2)}; }
        };
        var planned = new PlannedInputPattern(source, List.of(Map.of(keys[0], demand)));
        var inventory = new ListCraftingInventory(key -> {});
        inventory.insert(keys[0], initial, Actionable.MODULATE);
        long[] progress = {request}, accepted = {0};
        Throwable[] callbackFailure = {null};
        var providers = new ArrayList<ICraftingProvider>();
        int providerCount = 1 + random.nextInt(8);
        for (int index = 0; index < providerCount; index++) {
            long advertised = random.nextInt(40) * scale;
            long preparedLimit = random.nextInt(40) * scale;
            long acceptance = random.nextInt(40) * scale;
            int[] prepareCalls = {0}, pushCalls = {0};
            var provider = (IBatchCraftingProvider) Proxy.newProxyInstance(
                    BatchStockPressure.class.getClassLoader(), new Class<?>[] {IBatchCraftingProvider.class},
                    (proxy, method, arguments) -> {
                        try {
                            return switch (method.getName()) {
                                case "getBatchCapacity" -> advertised;
                                case "getBatchDispatchMode" -> BatchDispatchMode.NORMAL;
                                case "isBusy", "supportsSharedBatchInputs" -> false;
                                case "prepareBatch" -> {
                                    equal(1, ++prepareCalls[0], "single preparation per provider");
                                    equal(initial - demand, held(inventory, keys[0]), "prepare before bulk reservation");
                                    KeyCounter[] prototype = (KeyCounter[]) arguments[1];
                                    equal(demand, prototype[0].get(keys[0]), "prepared input identity");
                                    long available = (long) arguments[2];
                                    if (available > advertised || available < 1)
                                        throw new AssertionError("prepared offer bound");
                                    yield new PreparedBatch() {
                                        public long capacity() { return preparedLimit; }
                                        public long push(long copies) {
                                            equal(1, ++pushCalls[0], "one final offer per provider");
                                            if (copies <= 0 || copies > Math.min(available, preparedLimit))
                                                throw new AssertionError("final offer exceeds prepared capacity");
                                            long consumed = Math.min(copies, acceptance);
                                            accepted[0] += consumed;
                                            return copies - consumed;
                                        }
                                    };
                                }
                                case "pushBatch" -> throw new AssertionError("prepared contract bypassed");
                                default -> null;
                            };
                        } catch (Throwable error) {
                            callbackFailure[0] = error;
                            throw error;
                        }
                    });
            providers.add(provider);
        }
        var schedule = new TickProviderDispatchSchedule();
        schedule.beginTick(1);
        schedule.candidates(null, 1, source, () -> providers);
        var waiting = new ListCraftingInventory(key -> {});
        var handle = new BatchTaskHandle() {
            public IPatternDetails details() { return planned; }
            public long getValue() { return progress[0]; }
            public void setValue(long value) { progress[0] = value; }
        };
        var tasks = new ArrayList<BatchTaskHandle>(List.of(handle));
        var job = new BatchJobView() {
            public Level level() { return null; }
            public java.util.UUID craftingId() { return null; }
            public java.util.Iterator<BatchTaskHandle> taskIterator() { return tasks.iterator(); }
            public ListCraftingInventory waitingFor() { return waiting; }
            public void addContainerMaxItems(long count, AEKeyType type) { }
            public void failDispatch(String reason, Throwable cause) { throw new AssertionError(reason, cause); }
        };
        var energy = (IEnergyService) Proxy.newProxyInstance(BatchStockPressure.class.getClassLoader(),
                new Class<?>[] {IEnergyService.class}, (proxy, method, arguments) ->
                        method.getName().equals("extractAEPower") ? arguments[0] : null);
        var result = BatchExecutor.runBatchOnly(16, BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH, null, energy,
                job, inventory, new HashMap<>(), () -> {}, Map.of(keys[0], reserve), 16, maximum, false, schedule);
        // Admission errors are caught by production; never let a swallowed oracle assertion pass.
        if (callbackFailure[0] != null) throw new AssertionError("provider callback", callbackFailure[0]);
        if (accepted[0] > Math.min(request, maximum) || accepted[0] < 0)
            throw new AssertionError("accepted copies exceed CPU budget");
        equal(accepted[0], result.dispatchedCopies(), "reported provider acceptance");
        equal(request - accepted[0], progress[0], "job progress conservation");
        equal(initial - demand * accepted[0], held(inventory, keys[0]), "provider input conservation");
        equal(2 * accepted[0], held(waiting, keys[1]), "expected outputs");
        equal(0, held(inventory, keys[1]), "alternative input untouched");
        if (held(inventory, keys[0]) < Math.min(initial, reserve)) throw new AssertionError("reserved input spent");
        if (ParallelBatchCpuHelper.currentBatchCapacityLimiter(planned, inventory) != null)
            throw new AssertionError("provider limiter scope leaked");
    }

    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[1]);
        var random = new Random(seed);
        var keyClass = Class.forName(PlannedInputDispatchTest.class.getName() + "$TestKey");
        var constructor = keyClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        AEKey[] keys = {(AEKey) constructor.newInstance("a"), (AEKey) constructor.newInstance("b")};
        int cases = 20_000, limiterCalls = 0, throwsSeen = 0, rejected = 0, trillionCases = 0;
        long started = System.nanoTime();
        for (int test = 0; test < cases; test++) {
            try {
                int slots = 1 + random.nextInt(6), mode = random.nextInt(6);
                long scale = test % 7 == 0 ? 1_000_000_000_000L : 1L;
                if (scale != 1) trillionCases++;
                var inputs = new IPatternDetails.IInput[slots];
                var allocation = new ArrayList<Map<AEKey, Long>>();
                long[] demand = new long[2], stock = new long[2], reserve = new long[2];
                for (int slot = 0; slot < slots; slot++) {
                    long unit = 1 + random.nextInt(4), multiplier = 1 + random.nextInt(5);
                    long aUnits = random.nextInt((int) multiplier + 1), bUnits = multiplier - aUnits;
                    inputs[slot] = new Input(keys[0], keys[1], unit, multiplier);
                    var fixed = new HashMap<AEKey, Long>();
                    if (aUnits > 0) fixed.put(keys[0], aUnits * unit);
                    if (bUnits > 0) fixed.put(keys[1], bUnits * unit);
                    allocation.add(fixed);
                    demand[0] += aUnits * unit;
                    demand[1] += bUnits * unit;
                }
                long requested = random.nextInt(101) * scale;
                var inventory = new ListCraftingInventory(key -> {});
                var reserved = new HashMap<AEKey, Long>();
                long available = requested;
                for (int k = 0; k < 2; k++) {
                    stock[k] = random.nextInt(1601) * scale;
                    reserve[k] = random.nextInt(1801) * scale;
                    inventory.insert(keys[k], stock[k], Actionable.MODULATE);
                    reserved.put(keys[k], reserve[k]);
                    if (demand[k] > 0) available = Math.min(available,
                            Math.max(0, stock[k] - reserve[k]) / demand[k]);
                }
                long possible = available, limit = random.nextInt(101) * scale;
                var planned = new PlannedInputPattern(new Pattern(inputs), allocation);
                int[] calls = {0};
                var failure = new IllegalStateException("deliberate preparation failure");
                ParallelBatchCpuHelper.BatchCapacityLimiter limiter = (prototype, copies) -> {
                    calls[0]++;
                    equal(possible, copies, "available copies");
                    for (int k = 0; k < 2; k++) {
                        equal(stock[k] - demand[k], held(inventory, keys[k]), "prototype extraction");
                        long actual = 0;
                        for (var slot : prototype) actual += slot.get(keys[k]);
                        equal(demand[k], actual, "prototype composition");
                    }
                    if (mode == 5) throw failure;
                    return switch (mode) {
                        case 0 -> -1L;
                        case 1 -> 0L;
                        case 2 -> limit;
                        case 3 -> copies;
                        default -> Long.MAX_VALUE;
                    };
                };
                ParallelBatchCpuHelper.BulkResult result = null;
                boolean thrown = false;
                try {
                    result = ParallelBatchCpuHelper.withBatchCapacityLimiter(planned, inventory, limiter,
                            () -> ParallelBatchCpuHelper.bulkExtract(planned, inventory, requested,
                                    false, reserved, null));
                } catch (IllegalStateException ex) {
                    if (ex != failure) throw ex;
                    thrown = true;
                    throwsSeen++;
                }
                equal(possible > 0 ? 1 : 0, calls[0], "limiter calls");
                limiterCalls += calls[0];
                if (thrown != (mode == 5 && possible > 0)) throw new AssertionError("exception ownership");
                if (ParallelBatchCpuHelper.currentBatchCapacityLimiter(planned, inventory) != null)
                    throw new AssertionError("limiter scope leaked");
                long expected = mode <= 1 || mode == 5 ? 0 : mode == 2 ? Math.min(possible, limit) : possible;
                equal(expected, result == null ? 0 : result.actualCopies, "admitted copies");
                if (result == null) rejected++;
                for (int k = 0; k < 2; k++) equal(stock[k] - expected * demand[k],
                        held(inventory, keys[k]), "extraction conservation");
                if (result != null) {
                    for (int slot = 0; slot < slots; slot++) for (AEKey key : keys)
                        equal(allocation.get(slot).getOrDefault(key, 0L) * expected,
                                result.scaledInputs[slot].get(key), "slot ownership");
                    long accepted = random.nextLong(expected + 1);
                    ParallelBatchCpuHelper.markDispatched(result, accepted);
                    ParallelBatchCpuHelper.reinject(result, Long.MAX_VALUE, inventory);
                    // A repeated refund must not return provider-owned or already returned copies.
                    ParallelBatchCpuHelper.reinject(result, Long.MAX_VALUE, inventory);
                    for (int k = 0; k < 2; k++) equal(stock[k] - accepted * demand[k],
                            held(inventory, keys[k]), "dispatch/refund conservation");
                    for (KeyCounter counter : result.scaledInputs) for (AEKey key : keys)
                        equal(0, counter.get(key), "remaining owned inputs");
                }
            } catch (Throwable error) {
                throw new AssertionError("seed=" + seed + " case=" + test, error);
            }
        }
        int providerCases = 2_000;
        for (int test = 0; test < providerCases; test++) {
            try {
                providerCase(random, keys);
            } catch (Throwable error) {
                throw new AssertionError("seed=" + seed + " provider-case=" + test, error);
            }
        }
        var result = Map.of("seed", seed, "cases", cases, "limiter_calls", limiterCalls,
                "preparation_exceptions", throwsSeen, "rejected_or_unavailable", rejected,
                "trillion_quantity_cases", trillionCases, "status", "PASS",
                "provider_cases", providerCases,
                "elapsed_ms", (System.nanoTime() - started) / 1_000_000.0);
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println(new GsonBuilder().create().toJson(result));
    }
}
