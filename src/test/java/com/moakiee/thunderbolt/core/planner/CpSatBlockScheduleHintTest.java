package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import java.math.BigInteger;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatBlockScheduleHintTest {
    @BeforeAll static void loadNative() { CpSatBridge.initialize(); }

    @Test
    void seedThenTrillionRoundsHasACompressedHintWithoutChangingAnyConstraint() {
        long q = 1_000_000_000_000L;
        var scheduled = fixture(q, 1, q);
        var unscheduled = fixture(q, 0, q);
        assertEquals(scheduled.model.model().getVariablesList(), unscheduled.model.model().getVariablesList());
        assertEquals(scheduled.model.model().getConstraintsList(), unscheduled.model.model().getConstraintsList());
        assertFalse(unscheduled.model.model().hasSolutionHint());
        assertEquals("", scheduled.model.validate());

        var hints = hints(scheduled.model);
        assertEquals(1L, hints.get(scheduled.repeats[2].getIndex()));
        assertEquals(q, hints.get(scheduled.repeats[5 + 3].getIndex()));
        // Independently replay the hinted prefix from individual recipe pre/post arcs, including
        // external raw losses. Repetition counts cannot scale the traversal with q.
        BigInteger[] balance = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(q),
                BigInteger.ONE, BigInteger.ZERO};
        long[][] pre = {{0, 1, 0, 0, 0}, {1, 0, 1, 0, 0}, {0, 0, 0, 1, 0}};
        long[][] post = {{1, 0, 0, 0, 1}, {0, 1, 0, 0, 0}, {1, 0, 0, 0, 0}};
        int[][] steps = {{0}, {1}, {2}, {1, 0}, {0, 1}};
        long[] counts = new long[3];
        for (int stage = 0; stage < 4; stage++) {
            int selected = 0;
            for (int block = 0; block < steps.length; block++) {
                long n = hints.get(scheduled.repeats[stage * steps.length + block].getIndex());
                if (n == 0) continue;
                selected++;
                BigInteger[] required = new BigInteger[5], delta = new BigInteger[5];
                java.util.Arrays.fill(required, BigInteger.ZERO);
                java.util.Arrays.fill(delta, BigInteger.ZERO);
                for (int recipe : steps[block]) {
                    counts[recipe] += n;
                    for (int i = 0; i < 5; i++) {
                        required[i] = required[i].max(BigInteger.valueOf(pre[recipe][i]).subtract(delta[i]));
                        delta[i] = delta[i].add(BigInteger.valueOf(post[recipe][i] - pre[recipe][i]));
                    }
                }
                for (int i = 0; i < 5; i++) {
                    BigInteger reserve = required[i].add(delta[i].negate().max(BigInteger.ZERO)
                            .multiply(BigInteger.valueOf(n - 1)));
                    assertTrue(balance[i].compareTo(reserve) >= 0);
                    balance[i] = balance[i].add(delta[i].multiply(BigInteger.valueOf(n)));
                }
            }
            assertTrue(selected <= 1);
        }
        assertArrayEquals(new long[]{q, q, 1}, counts);
        assertEquals(BigInteger.valueOf(q), balance[4]);
        assertEquals(BigInteger.ZERO, balance[2]);
        assertEquals(BigInteger.ZERO, balance[3]);
    }

    @Test
    void shortRawStockAndInsufficientStagesDeclineTheHint() {
        assertFalse(fixture(10, 1, 9).model.model().hasSolutionHint());
        assertFalse(fixture(10, 1, 10, 1).model.model().hasSolutionHint());
        assertFalse(fixture(100_000_000_000_000_000L, 1,
                100_000_000_000_000_000L).model.model().hasSolutionHint(),
                "the optional block-domain cap must remain in force");
    }

    private record Fixture(CpModel model, IntVar[] repeats) { }

    private static Fixture fixture(long q, long seed, long raw) {
        return fixture(q, seed, raw, 4);
    }

    private static Fixture fixture(long q, long seed, long raw, int stages) {
        var model = new CpModel();
        IntVar[] fired = {model.newIntVar(0, q, "bt"), model.newIntVar(0, q, "ab"),
                model.newIntVar(0, 1, "seed")};
        IntVar[] used = new IntVar[5], missing = new IntVar[5];
        for (int i = 0; i < 5; i++) {
            used[i] = model.newIntVar(0, q, "used" + i);
            missing[i] = model.newIntVar(0, q, "missing" + i);
        }
        // Wire rows are independent hand-calculated summaries: group, three firing counts,
        // five required amounts, five deltas. Both round rotations omit the one-time seed.
        long[][] blocks = {
                {0, 1, 0, 0, 0, 1, 0, 0, 0, 1, -1, 0, 0, 1},
                {0, 0, 1, 0, 1, 0, 1, 0, 0, -1, 1, -1, 0, 0},
                {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, -1, 0},
                {0, 1, 1, 0, 1, 0, 1, 0, 0, 0, 0, -1, 0, 1},
                {0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, -1, 0, 1}};
        var produced = SparseLongMatrix.fromDense(new long[][]{
                {1, 0, 0, 0, 1}, {0, 1, 0, 0, 0}, {1, 0, 0, 0, 0}});
        IntVar[] repeats = CpSatExecutionBlocks.add(model, fired, used, missing, produced,
                new int[]{0, 1, 0}, new int[]{0, 0, 1, 2, 3}, new long[]{q, q, 1},
                blocks, stages, new long[]{0, 0, raw, seed, 0});
        return new Fixture(model, repeats);
    }

    private static HashMap<Integer, Long> hints(CpModel model) {
        var values = new HashMap<Integer, Long>();
        var hint = model.model().getSolutionHint();
        for (int i = 0; i < hint.getVarsCount(); i++)
            assertNull(values.put(hint.getVars(i), hint.getValues(i)), "duplicate hints invalidate the model");
        return values;
    }
}
