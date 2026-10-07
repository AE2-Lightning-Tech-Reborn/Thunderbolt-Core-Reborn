package com.moakiee.thunderbolt.core.crafting.planner;

/** Completeness refers only to the admitted raw-stock witness domain, not an arbitrary graph. */
record BatchOptimizationResult<K>(Status status, CraftPlan<K> plan) {
    enum Status { UNSUPPORTED, EXHAUSTED, COMPLETE }

    static <K> BatchOptimizationResult<K> unsupported(CraftPlan<K> plan) {
        return new BatchOptimizationResult<>(Status.UNSUPPORTED, plan);
    }

    static <K> BatchOptimizationResult<K> exhausted(CraftPlan<K> plan) {
        return new BatchOptimizationResult<>(Status.EXHAUSTED, plan);
    }

    static <K> BatchOptimizationResult<K> complete(CraftPlan<K> plan) {
        return new BatchOptimizationResult<>(Status.COMPLETE, plan);
    }
}
