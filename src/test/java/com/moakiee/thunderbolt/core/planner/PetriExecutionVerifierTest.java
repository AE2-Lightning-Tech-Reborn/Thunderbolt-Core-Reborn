package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class PetriExecutionVerifierTest {
    @Test void exactVerdictsAndCertificatesAgreeWithExhaustiveTinyNets() {
        Random random = new Random(0x50455452494cL);
        for (int sample = 0; sample < 1000; sample++) {
            int places = 2 + random.nextInt(3), recipes = 2 + random.nextInt(3);
            long[][] pre = new long[recipes][places], post = new long[recipes][places];
            long[] counts = new long[recipes], stock = new long[places];
            for (int i = 0; i < places; i++) stock[i] = random.nextInt(5);
            for (int r = 0; r < recipes; r++) {
                counts[r] = random.nextInt(4);
                for (int i = 0; i < places; i++) {
                    pre[r][i] = random.nextInt(3);
                    post[r][i] = random.nextInt(3);
                }
            }
            int target = random.nextInt(places);
            long amount = 1 + random.nextInt(3);
            boolean reachable = oracle(pre, post, counts.clone(), stock.clone(), target, amount, new HashSet<>());
            var checked = PetriExecutionVerifier.verify(pre, post, counts, big(stock),
                    new int[] {0}, target, amount, new PetriExecutionVerifier.Budget(4096, 1_000_000));
            assertEquals(reachable ? PetriExecutionVerifier.Status.EXECUTABLE : PetriExecutionVerifier.Status.UNREACHABLE,
                    checked.status(), "sample=" + sample);
            if (reachable) {
                long[] certificateStock = new long[places];
                for (int i = 0; i < places; i++) {
                    certificateStock[i] = checked.certificate().required()[i].longValueExact();
                    assertTrue(certificateStock[i] <= stock[i]);
                }
                assertTrue(oracle(pre, post, counts.clone(), certificateStock, target, amount, new HashSet<>()),
                        "the independent oracle must execute the certificate's own marking");
            }
            var fallback = PetriExecutionVerifier.orderedCertificate(pre, post, counts, target, amount);
            long[] fallbackStock = Arrays.stream(fallback.required()).mapToLong(BigInteger::longValueExact).toArray();
            assertTrue(oracle(pre, post, counts.clone(), fallbackStock, target, amount, new HashSet<>()));
        }
    }

    @Test void repeatedExecutionBlockHandlesTrillionsWithoutPerFiringExpansion() {
        long q = 1_000_000_000_000L;
        long[][] pre = {{1,0,1,0}, {0,1,0,0}};
        long[][] post = {{0,1,0,0}, {1,0,0,1}};
        var checked = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> PetriExecutionVerifier.verify(
                pre, post, new long[] {q,q}, big(new long[] {1,0,q,0}), new int[] {0,1},
                3, q, new PetriExecutionVerifier.Budget(16, 10_000)));
        assertEquals(PetriExecutionVerifier.Status.EXECUTABLE, checked.status());
        assertArrayEquals(big(new long[] {1,0,q,0}), checked.certificate().required());
        assertArrayEquals(big(new long[] {0,0,-q,q}), checked.certificate().delta());
    }

    @Test void insufficientSearchBudgetIsUnknownAndDoesNotBecomeADeadlockProof() {
        long[][] pre = {{1,0},{0,1}}, post = {{0,1},{1,0}};
        var checked = PetriExecutionVerifier.verify(pre, post, new long[] {1,1}, big(new long[] {1,0}),
                new int[] {0,1}, 0, 1, new PetriExecutionVerifier.Budget(0, 0));
        assertEquals(PetriExecutionVerifier.Status.UNKNOWN, checked.status());
        assertNull(checked.certificate());
    }

    @Test void aWrongMaximalBatchIsBacktrackedRatherThanUsedAsAnInfeasibilityProof() {
        // Need R0 once, R1, then R0 again. Taking both A at the first step blocks R1's A input.
        long[][] pre = {{1,0,0}, {1,1,0}}, post = {{0,1,0}, {1,0,1}};
        var checked = PetriExecutionVerifier.verify(pre, post, new long[] {2,1}, big(new long[] {2,0,0}),
                new int[] {0,1}, 2, 1, new PetriExecutionVerifier.Budget(32, 10_000));
        assertEquals(PetriExecutionVerifier.Status.EXECUTABLE, checked.status());
    }

    @Test void externalCancellationPropagates() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> PetriExecutionVerifier.verify(
                    new long[][] {{1}}, new long[][] {{1}}, new long[] {1}, big(new long[] {1}),
                    new int[] {0}, 0, 1, new PetriExecutionVerifier.Budget(10, 100)));
        } finally { Thread.interrupted(); }
    }

    private static BigInteger[] big(long[] values) {
        return Arrays.stream(values).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
    }

    @Test void independentOracleHandlesDeepPlansAndStillRejectsInsufficientStock() {
        long[][] pre = {{1, 0}}, post = {{0, 1}};
        assertTrue(oracle(pre, post, new long[] {20_000}, new long[] {20_000, 0},
                1, 20_000, new HashSet<>()));
        assertFalse(oracle(pre, post, new long[] {20_000}, new long[] {19_999, 0},
                1, 20_000, new HashSet<>()));
    }

    /** One firing at a time, with no batching, prefix summaries, or production verifier calls. */
    static boolean oracle(long[][] pre, long[][] post, long[] remaining, long[] marking,
                                  int target, long amount, Set<String> seen) {
        record State(long[] remaining, long[] marking) {}
        var pending = new ArrayDeque<State>();
        pending.push(new State(remaining, marking));
        while (!pending.isEmpty()) {
            var state = pending.pop();
            if (!seen.add(Arrays.toString(state.remaining()))) continue;
            if (Arrays.stream(state.remaining()).allMatch(n -> n == 0)) {
                if (state.marking()[target] >= amount) return true;
                continue;
            }
            // Reverse push order preserves the recursive oracle's depth-first traversal.
            for (int r = state.remaining().length - 1; r >= 0; r--) {
                if (state.remaining()[r] == 0) continue;
                boolean enabled = true;
                for (int i = 0; i < state.marking().length; i++)
                    if (state.marking()[i] < pre[r][i]) enabled = false;
                if (!enabled) continue;
                long[] next = state.marking().clone(), counts = state.remaining().clone();
                counts[r]--;
                for (int i = 0; i < next.length; i++) next[i] += post[r][i] - pre[r][i];
                pending.push(new State(counts, next));
            }
        }
        return false;
    }
}
