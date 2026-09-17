package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PetriReplenishmentSearchTest {
    @Test void completeTinySearchMatchesIndependentEnumerationWithBoundaryRestrictions() {
        var random = new Random(17092026);
        for (int sample = 0; sample < 200; sample++) {
            long[][] pre = new long[3][3], post = new long[3][3];
            long[] counts = new long[3], stock = new long[3];
            boolean[] allowed = new boolean[3];
            for (int i = 0; i < 3; i++) { stock[i] = random.nextInt(3); allowed[i] = random.nextBoolean(); }
            for (int r = 0; r < 3; r++) {
                counts[r] = random.nextInt(3);
                for (int i = 0; i < 3; i++) { pre[r][i] = random.nextInt(3); post[r][i] = random.nextInt(3); }
            }
            int target = random.nextInt(3), amount = 1 + random.nextInt(3);
            long expected = enumerate(pre, post, counts, stock, new long[3], allowed, target, amount);
            var result = PetriReplenishmentSearch.search(pre, post, counts, stock, allowed, new int[3],
                    target, amount, List.of(), new PetriExecutionVerifier.Budget(8192, 4_194_304), 8192);
            if (expected == Long.MAX_VALUE) assertNull(result, "sample=" + sample);
            else {
                assertNotNull(result, "sample=" + sample);
                assertEquals(expected, Arrays.stream(result.missing()).sum(), "sample=" + sample);
                long[] initial = stock.clone();
                for (int i = 0; i < 3; i++) { initial[i] += result.missing()[i]; if (!allowed[i]) assertEquals(0, result.missing()[i]); }
                assertTrue(PetriExecutionVerifierTest.oracle(pre, post, counts, initial, target, amount, new java.util.HashSet<>()));
            }
        }
    }

    @Test void repeatedWitnessSearchDoesNotExpandTrillionsOfFirings() {
        long q = 1_000_000_000_000L;
        long[][] pre = {{1,0,1,0},{0,1,0,0}}, post = {{0,1,0,0},{1,0,0,1}};
        var blocks = PetriBlockCatalog.build(pre, post, new int[]{0,0,1,2}, new int[]{1,0}, Set.of(0), List.of());
        var result = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> PetriReplenishmentSearch.search(
                pre, post, new long[]{q,q}, new long[]{0,0,q,0}, new boolean[]{true,true,true,false},
                new int[]{2,1,2,0}, 3, q, blocks, new PetriExecutionVerifier.Budget(128, 100_000), 128));
        assertNotNull(result);
        assertArrayEquals(new long[]{1,0,0,0}, result.missing());
    }

    @Test void traceCheckerRejectsAValidBalanceWithTheWrongOriginalFiringVector() {
        long[][] pre = {{1,0},{0,1}}, post = {{0,1},{1,0}};
        var trace = new PetriExecutionTrace.Fire(0, 1);
        assertNull(PetriExecutionTrace.certificate(trace, pre, post, new long[]{1,1}, 0, 1));
        var proof = PetriExecutionTrace.certificate(trace, pre, post, new long[]{1,0}, 1, 1);
        assertNotNull(proof);
        assertArrayEquals(new java.math.BigInteger[]{java.math.BigInteger.ONE, java.math.BigInteger.ZERO}, proof.required());
    }

    /** Independent unit firing orders; missing inputs are supplied only at permitted boundaries. */
    private static long enumerate(long[][] pre, long[][] post, long[] remaining, long[] inventory,
                                  long[] missing, boolean[] allowed, int target, long amount) {
        if (Arrays.stream(remaining).allMatch(n -> n == 0)) {
            long extra = Math.max(0, amount - inventory[target]);
            return extra > 0 && !allowed[target] ? Long.MAX_VALUE : Arrays.stream(missing).sum() + extra;
        }
        long best = Long.MAX_VALUE;
        for (int r = 0; r < remaining.length; r++) {
            if (remaining[r] == 0) continue;
            long[] next = inventory.clone(), counts = remaining.clone(), supply = missing.clone();
            boolean valid = true;
            for (int i = 0; i < inventory.length; i++) {
                long shortage = Math.max(0, pre[r][i] - next[i]);
                if (shortage > 0 && !allowed[i]) { valid = false; break; }
                supply[i] += shortage;
                next[i] += shortage + post[r][i] - pre[r][i];
            }
            if (valid) { counts[r]--; best = Math.min(best, enumerate(pre, post, counts, next, supply, allowed, target, amount)); }
        }
        return best;
    }
}
