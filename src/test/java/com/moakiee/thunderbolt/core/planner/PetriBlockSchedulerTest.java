package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.SparseLongMatrix;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class PetriBlockSchedulerTest {
    @Test void repeatedRoundPreservesItsStartupPrefixAndExternalLosses() {
        long q = 1_000_000_000_000L;
        var pre = SparseLongMatrix.fromDense(new long[][]{{1, 0, 1, 0}, {0, 1, 0, 0}});
        var post = SparseLongMatrix.fromDense(new long[][]{{0, 1, 0, 0}, {1, 0, 0, 1}});
        var blocks = PetriBlockCatalog.build(pre, post, new int[]{0, 0, 1, 2},
                new int[]{1, 0}, Set.of(0), List.of(), false);
        BigInteger[] initial = {BigInteger.ONE, BigInteger.ZERO, BigInteger.valueOf(q), BigInteger.ZERO};
        var budget = new PetriExecutionVerifier.Budget(8, 100_000);
        var proof = PetriBlockScheduler.schedule(pre, post, new long[]{q, q}, initial, 3, q, blocks, budget);
        assertNotNull(proof);
        assertArrayEquals(initial, proof.required());
        assertArrayEquals(new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.valueOf(-q), BigInteger.valueOf(q)}, proof.delta());
        assertTrue(budget.remainingNodes() >= 5, "repetition count must not determine traversal work");
        assertNotNull(PetriBlockScheduler.schedule(pre, post, new long[]{q, q},
                new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(q), BigInteger.ZERO},
                3, q, blocks, new PetriExecutionVerifier.Budget(8, 100_000)),
                "a concrete seed in the other state needs the opposite round rotation");
        assertNull(PetriBlockScheduler.schedule(pre, post, new long[]{q, q},
                new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(q), BigInteger.ZERO},
                3, q, blocks, new PetriExecutionVerifier.Budget(8, 100_000)));
        assertNull(PetriBlockScheduler.schedule(pre, post, new long[]{q, q},
                new BigInteger[]{BigInteger.ONE, BigInteger.ZERO, BigInteger.valueOf(q - 1), BigInteger.ZERO},
                3, q, blocks, new PetriExecutionVerifier.Budget(8, 100_000)));
    }

    @Test void constructedTinyWitnessesAgreeWithAnIndependentUnitFiringOracle() {
        var random = new Random(20261008);
        int witnessed = 0;
        for (int sample = 0; sample < 300; sample++) {
            long[][] pre = new long[3][3], post = new long[3][3];
            long[] counts = new long[3], stock = new long[3];
            for (int i = 0; i < 3; i++) stock[i] = random.nextInt(5);
            for (int r = 0; r < 3; r++) {
                counts[r] = random.nextInt(3);
                for (int i = 0; i < 3; i++) { pre[r][i] = random.nextInt(3); post[r][i] = random.nextInt(3); }
            }
            int target = random.nextInt(3), amount = 1 + random.nextInt(3);
            var initial = Arrays.stream(stock).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
            var proof = PetriBlockScheduler.schedule(SparseLongMatrix.fromDense(pre), SparseLongMatrix.fromDense(post),
                    counts, initial, target, amount, List.of(), new PetriExecutionVerifier.Budget(256, 100_000));
            if (proof != null) {
                witnessed++;
                assertTrue(PetriExecutionVerifierTest.oracle(pre, post, counts, stock, target, amount,
                        new java.util.HashSet<>()), "sample=" + sample);
                for (int i = 0; i < 3; i++) assertTrue(proof.required()[i].compareTo(initial[i]) <= 0);
            }
        }
        assertTrue(witnessed > 30, "the oracle check must exercise successful schedules");
    }

    @Test void emptyBudgetStopsAndCallerCancellationPropagates() {
        var pre = SparseLongMatrix.fromDense(new long[][]{{1, 0}});
        var post = SparseLongMatrix.fromDense(new long[][]{{0, 1}});
        BigInteger[] initial = {BigInteger.ONE, BigInteger.ZERO};
        assertNull(PetriBlockScheduler.schedule(pre, post, new long[]{1}, initial, 1, 1, List.of(),
                new PetriExecutionVerifier.Budget(0, 1000)));
        assertNull(PetriBlockScheduler.schedule(pre, post, new long[]{1}, initial, 1, 1, List.of(),
                new PetriExecutionVerifier.Budget(10, 0)));
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> PetriBlockScheduler.schedule(pre, post,
                    new long[]{1}, initial, 1, 1, List.of(), new PetriExecutionVerifier.Budget(10, 1000)));
        } finally { Thread.interrupted(); }
    }
}
