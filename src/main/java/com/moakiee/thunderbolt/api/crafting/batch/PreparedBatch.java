package com.moakiee.thunderbolt.api.crafting.batch;

/**
 * Side-effect-free admission prepared from concrete single-copy inputs. Used only within the
 * current dispatch call; never cache it across ticks. Preparing must not transfer input ownership.
 */
public interface PreparedBatch {
    /** Positive accepted-copy bound, or zero when the batch is currently unavailable. */
    long capacity();

    /**
     * Submit at most the offered copies once, returning unaccepted copies as in
     * {@link IBatchCraftingProvider#pushBatch}. A smaller offer must be revalidated before commit.
     */
    long push(long maxCraft);
}
