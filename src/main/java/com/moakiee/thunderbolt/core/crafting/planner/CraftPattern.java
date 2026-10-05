package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A single crafting pattern (recipe) in the planner's view: it produces {@code outputAmount} of
 * {@code output} per firing, consuming the given {@code inputs}.
 *
 * <p>The primary {@code output} is modeled directly; every other item produced per firing is a
 * {@link CraftOutput byproduct}. The v1 closed-form planner ignores byproducts (and its caller
 * declines such patterns), while the v2 planner ({@code CraftPlannerV2}) routes byproducts into a
 * shared pool for opportunistic reuse. {@code source} is an opaque handle back to the original recipe
 * object (e.g. AE2 {@code IPatternDetails}); the planner uses its identity to group concrete fuzzy
 * expansions of one real recipe and as the {@link CraftPlan#firings()} key.
 *
 * @param <K> item key type
 */
public final class CraftPattern<K> {

    private final K output;
    private final long outputAmount;
    private final BigInteger exactOutputAmount;
    private final List<CraftInput<K>> inputs;
    private final List<CraftOutput<K>> byproducts;
    private final Object source;
    private final List<List<CraftInput<K>>> executionSlots;
    private final int executionCost;
    private final boolean hasStatefulInputs;
    private final boolean hasHostFeedbackSeed;

    public CraftPattern(K output, long outputAmount, List<CraftInput<K>> inputs, Object source) {
        this(output, outputAmount, inputs, List.of(), source);
    }

    public CraftPattern(K output, long outputAmount, List<CraftInput<K>> inputs,
                        List<CraftOutput<K>> byproducts, Object source) {
        this(output, BigInteger.valueOf(outputAmount), inputs, byproducts, source);
    }

    public CraftPattern(K output, BigInteger outputAmount, List<CraftInput<K>> inputs,
                        List<CraftOutput<K>> byproducts, Object source) {
        this(output, outputAmount, inputs, byproducts, source, List.of());
    }

    public CraftPattern(K output, BigInteger outputAmount, List<CraftInput<K>> inputs,
                        List<CraftOutput<K>> byproducts, Object source,
                        List<List<CraftInput<K>>> executionSlots) {
        this(output, outputAmount, inputs, byproducts, source, executionSlots, 1, false);
    }

    private CraftPattern(K output, BigInteger outputAmount, List<CraftInput<K>> inputs,
                         List<CraftOutput<K>> byproducts, Object source,
                         List<List<CraftInput<K>>> executionSlots, int executionCost, boolean tagConversion) {
        if (tagConversion ? executionCost != 0 : executionCost <= 0)
            throw new IllegalArgumentException("ordinary executionCost must be positive; only tag conversion is free");
        this.output = Objects.requireNonNull(output, "output");
        if (outputAmount.signum() <= 0) {
            throw new IllegalArgumentException("outputAmount must be > 0, was " + outputAmount);
        }
        this.exactOutputAmount = outputAmount;
        this.outputAmount = outputAmount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
        this.inputs = List.copyOf(inputs);
        boolean stateful = false;
        boolean hostFeedbackSeed = false;
        for (int slot = 0; slot < this.inputs.size(); slot++) {
            CraftInput<K> input = this.inputs.get(slot);
            stateful |= input.returned() || input.remainder() != null || input.reusableStockSource() != null;
            hostFeedbackSeed |= input.returned() && input.uses() == CraftInput.INFINITE_USES
                    && input.reusableStockSource() != null;
        }
        this.hasStatefulInputs = stateful;
        this.hasHostFeedbackSeed = hostFeedbackSeed;
        this.byproducts = normalizeByproducts(this.inputs, byproducts);
        this.source = source;
        this.executionSlots = executionSlots.isEmpty() ? List.of()
                : executionSlots.stream().map(List::copyOf).toList();
        this.executionCost = executionCost;
    }

    /**
     * A graph export's virtual member-to-tag edge, not a machine operation. The edge still
     * consumes one member and supplies one tag unit, so stock accounting and execution proofs
     * retain it. Exporters must identify tags explicitly; an ordinary 1:1 recipe is not free.
     */
    public static <K> CraftPattern<K> tagConversion(K member, K tag, Object source) {
        return new CraftPattern<>(tag, BigInteger.ONE, List.of(CraftInput.of(member, 1)),
                List.of(), source, List.of(), 0, true);
    }

    /**
     * An ordinary recipe with an explicit positive execution cost per firing. All outputs belong
     * to that same firing: byproducts do not add another execution charge. Zero remains reserved
     * for the pure member-to-tag factory, never inferred from a recipe's inputs or source.
     */
    public static <K> CraftPattern<K> weighted(K output, long outputAmount, List<CraftInput<K>> inputs,
            List<CraftOutput<K>> byproducts, Object source, int executionCost) {
        return weighted(output, BigInteger.valueOf(outputAmount), inputs, byproducts, source, executionCost);
    }

    public static <K> CraftPattern<K> weighted(K output, BigInteger outputAmount, List<CraftInput<K>> inputs,
            List<CraftOutput<K>> byproducts, Object source, int executionCost) {
        return new CraftPattern<>(output, outputAmount, inputs, byproducts, source, List.of(), executionCost, false);
    }

    /** Cost per firing: one by default, a positive explicit weight, or zero for tag conversion. */
    public int executionCost() {
        return executionCost;
    }

    boolean hasStatefulInputs() {
        return hasStatefulInputs;
    }

    boolean hasHostFeedbackSeed() {
        return hasHostFeedbackSeed;
    }

    /** Preserve objective/source metadata when an internal material projection drops side outputs. */
    CraftPattern<K> projectMaterials(List<CraftInput<K>> inputs, List<CraftOutput<K>> byproducts) {
        if (executionCost == 0) {
            if (!this.inputs.equals(inputs) || !byproducts.isEmpty())
                throw new IllegalArgumentException("a tag projection must retain its pure 1:1 material edge");
            return this;
        }
        return new CraftPattern<>(output, exactOutputAmount, inputs, byproducts, source,
                executionSlots, executionCost, false);
    }

    /**
     * Canonicalizes a consumed container's remainder into the ordinary byproduct list.
     *
     * <p>Older graph exporters already appended that output explicitly, while direct graph callers
     * often supplied only {@link CraftInput#remainder()}. An explicitly declared output for the same
     * key therefore wins for compatibility; otherwise all remainder amounts for that key are merged
     * and appended once. The input keeps its remainder metadata solely for bootstrap/order proofs.</p>
     */
    private static <K> List<CraftOutput<K>> normalizeByproducts(
            List<CraftInput<K>> inputs, List<CraftOutput<K>> declared) {
        List<CraftOutput<K>> result = new ArrayList<>(declared);
        Set<K> explicitKeys = new LinkedHashSet<>();
        for (CraftOutput<K> output : declared) explicitKeys.add(output.key());
        Map<K, BigInteger> inferred = new LinkedHashMap<>();
        for (CraftInput<K> input : inputs) {
            K remainder = input.remainder();
            if (remainder != null && !explicitKeys.contains(remainder)) {
                inferred.merge(remainder, input.exactAmount(), BigInteger::add);
            }
        }
        inferred.forEach((key, amount) -> result.add(CraftOutput.exact(key, amount)));
        return List.copyOf(result);
    }

    public K output() {
        return output;
    }

    public BigInteger exactOutputAmount() {
        return exactOutputAmount;
    }

    public long outputAmount() {
        return outputAmount;
    }

    public List<CraftInput<K>> inputs() {
        return inputs;
    }

    /** Extra outputs produced per firing besides the primary {@link #output()}. Empty if none. */
    public List<CraftOutput<K>> byproducts() {
        return byproducts;
    }

    /** Opaque handle to the originating recipe; may be {@code null} in tests. */
    public Object source() {
        return source;
    }

    /** Legacy opt-in allocation metadata. Built-in planners no longer populate this field. */
    @Deprecated
    public List<List<CraftInput<K>>> executionSlots() {
        return executionSlots;
    }

    @Override
    public String toString() {
        return "CraftPattern[" + outputAmount + "x" + output + " <- " + inputs + "]";
    }
}
