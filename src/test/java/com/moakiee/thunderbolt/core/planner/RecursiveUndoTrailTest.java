package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Checks the recursive mutation boundary against saved states, independent of trail representation. */
class RecursiveUndoTrailTest {
    private static final List<String> TRACKED = List.of("bpPool", "stockLeft", "reservedSelfSeeds",
            "reservedFeedbackSeedOutputs", "reservedFeedbackSeedHostOutputs", "reusableBorrowedDemand",
            "reusablePrivatePool", "reusablePool", "pinnedExactReusableStock", "fixedFiringQuota");
    private static final List<String> UNTRACKED = List.of("usedStock", "usedReusableStock", "missing", "grossDemand");

    @Test
    void everyAvailabilityMapRestoresItsStateWithoutReusingSequenceNumbers() throws Exception {
        var fixture = new Fixture();
        long sequence = 0;
        for (String name : TRACKED) {
            var map = fixture.map(name);
            fixture.put(map, "key", 7L);
            assertEquals(++sequence, fixture.number("availabilityState"), name);
            fixture.put(map, "key", 7L); // A same-value write is still a new availability state.
            assertEquals(++sequence, fixture.number("availabilityState"), name);
            fixture.rollback(0);
            assertTrue(map.isEmpty(), name);
            assertEquals(0, fixture.number("availabilityState"), name);
            assertEquals(sequence, fixture.number("nextAvailabilityState"), name);
        }
        for (String name : UNTRACKED) {
            fixture.put(fixture.map(name), "key", 7L);
            assertEquals(0, fixture.number("availabilityState"), name);
        }
        fixture.rollback(0);
        for (String name : UNTRACKED) assertTrue(fixture.map(name).isEmpty(), name);
    }

    @Test
    void rollbackDistinguishesAbsentAndZeroAndRestoresTheOriginalBoxedValue() throws Exception {
        var fixture = new Fixture();
        var map = fixture.map("bpPool");
        Long original = 123456L;
        map.put("present", original);
        map.put("zero", 0L);
        fixture.put(map, "present", 0L);
        fixture.put(map, "zero", 99L);
        fixture.put(map, "absent", 42L);
        fixture.rollback(0);
        assertSame(original, map.get("present"));
        assertTrue(map.containsKey("zero"));
        assertEquals(0L, map.get("zero"));
        assertFalse(map.containsKey("absent"));
    }

    @Test
    void equalKeysInAnIdentityMapRemainSeparateAcrossNestedRollback() throws Exception {
        var fixture = new Fixture();
        var map = new IdentityHashMap<Object, Long>();
        var first = new String("same");
        var second = new String("same");
        fixture.put(map, first, 1000L);
        int mark = fixture.size();
        fixture.put(map, second, 2000L);
        fixture.put(map, first, 3000L);
        fixture.rollback(mark);
        assertEquals(1, map.size());
        assertEquals(1000L, map.get(first));
        assertFalse(map.containsKey(second));
        fixture.rollback(0);
        assertTrue(map.isEmpty());
    }

    @Test
    void missingAndFiringsRestoreAfterSaturationAndReuseOfRolledBackSlots() throws Exception {
        var fixture = new Fixture();
        var pattern = new CraftPattern<>("T", 1, List.of(), "source");
        fixture.addMissing("raw", Sat.SAT - 1);
        fixture.bumpFiring(pattern, Sat.SAT - 1);
        int mark = fixture.size();
        var before = fixture.snapshot();
        fixture.addMissing("raw", 100L);
        fixture.bumpFiring(pattern, 100L);
        assertEquals(Sat.SAT, fixture.number("missingTotal"));
        assertEquals(Sat.SAT, fixture.map("firings").get(pattern));
        fixture.rollback(mark);
        assertEquals(before, fixture.snapshot());
        fixture.put(fixture.map("stockLeft"), "replacement", 1000L);
        fixture.addMissing("other", 9L);
        fixture.rollback(mark);
        assertEquals(before, fixture.snapshot());
        fixture.rollback(0);
        assertEquals(0, fixture.number("missingTotal"));
        assertTrue(fixture.map("missing").isEmpty());
        assertTrue(fixture.map("firings").isEmpty());
    }

    @Test
    void growthAndNestedRandomRollbackRestoreAllRecordedState() throws Exception {
        var fixture = new Fixture();
        var empty = fixture.snapshot();
        for (int i = 0; i < 1024; i++) fixture.put(fixture.map("stockLeft"), "grow" + i, i + 1L);
        fixture.rollback(0);
        assertEquals(empty, fixture.snapshot());

        var random = new Random(0x71a11);
        var marks = new ArrayList<Checkpoint>();
        marks.add(new Checkpoint(0, fixture.snapshot()));
        var patterns = List.of(new CraftPattern<>("A", 1, List.of(), "A"),
                new CraftPattern<>("A", 1, List.of(), "A"));
        for (int step = 0; step < 5000; step++) {
            int action = random.nextInt(8);
            if (action < 4) {
                String name = List.of("bpPool", "stockLeft", "usedStock", "grossDemand").get(action);
                fixture.put(fixture.map(name), "k" + random.nextInt(12), random.nextInt(1000));
            } else if (action == 4) {
                fixture.addMissing("k" + random.nextInt(12), random.nextBoolean() ? 1L : Sat.SAT);
            } else if (action == 5) {
                fixture.bumpFiring(patterns.get(random.nextInt(patterns.size())), random.nextInt(1000));
            } else if (action == 6) {
                marks.add(new Checkpoint(fixture.size(), fixture.snapshot()));
            } else {
                int index = random.nextInt(marks.size());
                var checkpoint = marks.get(index);
                long sequence = fixture.number("nextAvailabilityState");
                fixture.rollback(checkpoint.mark());
                assertEquals(checkpoint.state(), fixture.snapshot(), "step " + step);
                assertEquals(sequence, fixture.number("nextAvailabilityState"));
                marks.subList(index + 1, marks.size()).clear();
            }
        }
        fixture.rollback(0);
        assertEquals(empty, fixture.snapshot());
    }

    @Test
    void segmentCapacityStaysNearTheHighWaterMarkAndIsReusedAfterRollback() throws Exception {
        var fixture = new Fixture();
        int peak = 10_000;
        for (int i = 0; i < peak; i++) fixture.put(fixture.map("stockLeft"), "key", i + 1L);
        List<Object> segments = fixture.segments();
        int capacity = 0;
        for (Object segment : segments) {
            int slots = ((Object[]) member(segment, "maps")).length;
            assertTrue(slots <= 1024, "individual undo segments must stay bounded");
            capacity += slots;
        }
        assertTrue(capacity >= peak && capacity < peak + 1024,
                "retained record capacity must stay within one segment of the peak");
        fixture.rollback(16); // Exact first-segment boundary; append before crossing back.
        for (int i = 16; i < peak; i++) fixture.put(fixture.map("stockLeft"), "key", i + 1L);
        assertEquals(segments, fixture.segments(), "appending after rollback must reuse existing segments");
        fixture.rollback(0);
        assertTrue(fixture.map("stockLeft").isEmpty());
        for (int i = 0; i < peak; i++) fixture.put(fixture.map("stockLeft"), "key", i + 1L);
        assertEquals(segments, fixture.segments(), "a second peak must not allocate replacement segments");
        fixture.rollback(0);
    }

    @Test
    void rollbackClearsReferencesInEveryRetainedSegmentIncludingReusedScalarSlots() throws Exception {
        var fixture = new Fixture();
        for (int i = 0; i < 2048; i++) fixture.put(fixture.map("stockLeft"), "k" + i, i + 1L);
        fixture.rollback(0);
        for (int i = 0; i < 512; i++) fixture.addMissing("raw", i + 1L);
        fixture.rollback(0);
        for (Object segment : fixture.segments()) {
            for (String name : List.of("maps", "keys", "oldValues")) {
                for (Object reference : (Object[]) member(segment, name)) {
                    assertTrue(reference == null, "rolled-back " + name + " must not retain branch objects");
                }
            }
        }
        assertEquals(0, fixture.size());
        assertEquals(0, fixture.number("missingTotal"));
    }

    private static Object member(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private record State(Map<String, Map<Object, Long>> maps, long availability, long missing) { }
    private record Checkpoint(int mark, State state) { }

    private static final class Fixture {
        private final Object planner;
        private final Method put = method("put", Map.class, Object.class, long.class);
        private final Method missing = method("addMissing", Object.class, long.class);
        private final Method firing = method("bumpFiring", CraftPattern.class, long.class);
        private final Method rollback = method("rollback", int.class);

        Fixture() throws Exception {
            var diagnosticsType = Class.forName(CraftPlannerV2.class.getName() + "$DiagnosticsCollector");
            var searchType = Class.forName(CraftPlannerV2.class.getName() + "$SearchBudget");
            var diagnosticsConstructor = diagnosticsType.getDeclaredConstructor(
                    int.class, int.class, CraftPlannerV2.PlanningSession.class);
            diagnosticsConstructor.setAccessible(true);
            var diagnostics = diagnosticsConstructor.newInstance(1, 1, new CraftPlannerV2.PlanningSession<>());
            var constructor = CraftPlannerV2.class.getDeclaredConstructor(CraftGraph.class, int.class,
                    searchType, BoundedIntegerLinearSolver.WorkBudget.class, diagnosticsType);
            constructor.setAccessible(true);
            planner = constructor.newInstance(CraftGraph.builder().build(), 1, null, null, diagnostics);
            method("initializeRecursiveExecution").invoke(planner);
        }

        void put(Map<Object, Long> map, Object key, long value) throws Exception { put.invoke(planner, map, key, value); }
        void addMissing(String key, long value) throws Exception { missing.invoke(planner, key, value); }
        void bumpFiring(CraftPattern<?> pattern, long value) throws Exception { firing.invoke(planner, pattern, value); }
        void rollback(int mark) throws Exception { rollback.invoke(planner, mark); }
        long number(String name) throws Exception { return field(name).getLong(planner); }

        @SuppressWarnings("unchecked")
        Map<Object, Long> map(String name) throws Exception { return (Map<Object, Long>) field(name).get(planner); }

        int size() throws Exception {
            Object trail = field("trail").get(planner);
            var size = trail.getClass().getDeclaredMethod("size");
            size.setAccessible(true);
            return (int) size.invoke(trail);
        }

        List<Object> segments() throws Exception {
            Object current = member(field("trail").get(planner), "current");
            while (member(current, "previous") != null) current = member(current, "previous");
            var result = new ArrayList<>();
            while (current != null) {
                result.add(current);
                current = member(current, "next");
            }
            return result;
        }

        State snapshot() throws Exception {
            var names = new ArrayList<>(TRACKED);
            names.addAll(UNTRACKED);
            names.add("firings");
            var copies = new HashMap<String, Map<Object, Long>>();
            for (String name : names) {
                var values = map(name);
                copies.put(name, values instanceof IdentityHashMap ? new IdentityHashMap<>(values) : new HashMap<>(values));
            }
            return new State(copies, number("availabilityState"), number("missingTotal"));
        }

        private static Field field(String name) throws Exception {
            var field = CraftPlannerV2.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static Method method(String name, Class<?>... types) throws Exception {
            var method = CraftPlannerV2.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method;
        }
    }
}
