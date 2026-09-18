package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpModelProto;
import com.google.ortools.sat.IntVar;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Proof-preserving elimination of circulation from pairs of existing cyclic balance rows. */
final class CpSatBalanceCuts {
    static final int MAX_CUTS = 128;
    private static final int MAX_PAIRS = 4_096;
    private static final int MAX_ELIMINATIONS = 4_096;
    private static final BigInteger SAFE = BigInteger.valueOf(Long.MAX_VALUE / 2L);

    private CpSatBalanceCuts() { }

    private record Row(Map<Integer, BigInteger> terms, BigInteger minimum) { }

    /**
     * All sources are unconditional inequalities already in the model. Positive integer multiples
     * cancel a shared firing variable; normalization rounds the lower bound upwards only after
     * dividing every integer coefficient by their gcd. Thus every original integer solution still
     * satisfies every added row. No firing domain, recipe policy or execution certificate changes.
     *
     * <p>Only pairs of original rows in the same cyclic rank group are considered, and only results
     * with fewer firing variables than the larger source and no positive firing coefficient are
     * retained: these expose a resource deficit without another source of internal circulation.
     * Work and added rows have
     * fixed structural caps, independent of stock, requested amounts and integer-domain widths.
     * Exhausting these caps merely omits redundant constraints; it is never an infeasibility proof.
     */
    static int add(CpModel model, IntVar[] firings, int[] groups, int[] demandRows, int[] balanceRows) {
        CpModelProto source = model.model();
        BitSet firingVariables = new BitSet();
        for (IntVar firing : firings) firingVariables.set(firing.getIndex());
        var members = new LinkedHashMap<Integer, List<Integer>>();
        for (int i = 0; i < groups.length; i++)
            members.computeIfAbsent(groups[i], ignored -> new ArrayList<>()).add(i);

        int added = 0, pairs = 0, eliminations = 0;
        var seen = new HashSet<Row>();
        for (var items : members.values()) {
            if (items.size() < 2) continue;
            var rows = new ArrayList<Row>();
            for (int item : items) {
                for (int index : new int[] {balanceRows[item], demandRows[item]}) {
                    if (index < 0) continue;
                    Row row = read(source, index);
                    if (row != null && seen.add(row)) rows.add(row);
                }
            }
            for (int i = 0; i < rows.size(); i++) for (int j = i + 1; j < rows.size(); j++) {
                if (++pairs > MAX_PAIRS) return added;
                Row left = rows.get(i), right = rows.get(j);
                int originalWidth = Math.max(width(left, firingVariables), width(right, firingVariables));
                for (var term : left.terms.entrySet()) {
                    int variable = term.getKey();
                    if (!firingVariables.get(variable)) continue;
                    BigInteger other = right.terms.get(variable);
                    if (other == null || term.getValue().signum() == other.signum()) continue;
                    if (++eliminations > MAX_ELIMINATIONS) return added;
                    Row combined = eliminate(left, right, term.getValue(), other);
                    if (width(combined, firingVariables) >= originalWidth || !seen.add(combined)) continue;
                    if (combined.terms.entrySet().stream().anyMatch(e ->
                            firingVariables.get(e.getKey()) && e.getValue().signum() > 0)) continue;
                    if (!safe(source, combined)) continue;
                    var linear = model.getBuilder().addConstraintsBuilder()
                            .setName("cyclic_balance_cut_" + added).getLinearBuilder();
                    combined.terms.forEach((v, c) -> {
                        linear.addVars(v); linear.addCoeffs(c.longValueExact());
                    });
                    linear.addDomain(combined.minimum.longValueExact()).addDomain(Long.MAX_VALUE);
                    if (++added >= MAX_CUTS) return added;
                }
            }
        }
        return added;
    }

    private static Row read(CpModelProto model, int index) {
        var constraint = model.getConstraints(index);
        if (!constraint.hasLinear() || constraint.getEnforcementLiteralCount() != 0) return null;
        var linear = constraint.getLinear();
        if (linear.getDomainCount() != 2) return null;
        boolean lower = linear.getDomain(1) == Long.MAX_VALUE;
        if (!lower && linear.getDomain(0) != Long.MIN_VALUE) return null;
        BigInteger sign = lower ? BigInteger.ONE : BigInteger.ONE.negate();
        var terms = new TreeMap<Integer, BigInteger>();
        for (int i = 0; i < linear.getVarsCount(); i++) terms.merge(linear.getVars(i),
                BigInteger.valueOf(linear.getCoeffs(i)).multiply(sign), BigInteger::add);
        return normalize(terms, BigInteger.valueOf(linear.getDomain(lower ? 0 : 1)).multiply(sign));
    }

    private static Row eliminate(Row left, Row right, BigInteger a, BigInteger b) {
        BigInteger gcd = a.gcd(b);
        BigInteger leftWeight = b.abs().divide(gcd), rightWeight = a.abs().divide(gcd);
        var terms = new TreeMap<Integer, BigInteger>();
        left.terms.forEach((v, c) -> terms.put(v, c.multiply(leftWeight)));
        right.terms.forEach((v, c) -> terms.merge(v, c.multiply(rightWeight), BigInteger::add));
        return normalize(terms, left.minimum.multiply(leftWeight).add(right.minimum.multiply(rightWeight)));
    }

    private static Row normalize(TreeMap<Integer, BigInteger> terms, BigInteger minimum) {
        terms.values().removeIf(c -> c.signum() == 0);
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : terms.values()) gcd = gcd.gcd(coefficient);
        if (gcd.compareTo(BigInteger.ONE) > 0) {
            BigInteger divisor = gcd;
            terms.replaceAll((v, c) -> c.divide(divisor));
            BigInteger[] division = minimum.divideAndRemainder(divisor);
            minimum = division[0].add(division[1].signum() > 0 ? BigInteger.ONE : BigInteger.ZERO);
        }
        return new Row(terms, minimum);
    }

    private static int width(Row row, BitSet firings) {
        int count = 0;
        for (int variable : row.terms.keySet()) if (firings.get(variable)) count++;
        return count;
    }

    private static boolean safe(CpModelProto model, Row row) {
        if (row.terms.isEmpty() && row.minimum.signum() <= 0) return false;
        if (row.minimum.abs().compareTo(SAFE) > 0) return false;
        BigInteger magnitude = BigInteger.ZERO;
        for (var term : row.terms.entrySet()) {
            if (term.getValue().abs().compareTo(SAFE) > 0) return false;
            var domain = model.getVariables(term.getKey()).getDomainList();
            BigInteger maximum = BigInteger.valueOf(domain.getFirst()).abs()
                    .max(BigInteger.valueOf(domain.getLast()).abs());
            magnitude = magnitude.add(term.getValue().abs().multiply(maximum));
            if (magnitude.compareTo(SAFE) > 0) return false;
        }
        return true;
    }
}
