package com.moakiee.thunderbolt.core.crafting.support;

import appeng.api.crafting.IPatternDetails;
import com.moakiee.thunderbolt.core.crafting.pattern.IBatchExecutionPattern;
import com.moakiee.thunderbolt.core.crafting.pattern.IProviderLookupPattern;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Resolves task wrappers independently for physical execution and provider registration. */
public final class CraftingPatternDelegates {
    public static IPatternDetails forBatchExecution(IPatternDetails details) {
        Objects.requireNonNull(details, "details");
        if (!(details instanceof IBatchExecutionPattern wrapped)) return details;
        var next = Objects.requireNonNull(wrapped.batchExecutionPattern(), "batchExecutionPattern");
        if (next == details) {
            throw new IllegalStateException("batch execution pattern delegates to itself");
        }
        // Ordinary patterns and a single wrapper need no cycle-detection allocation.
        return next instanceof IBatchExecutionPattern ? resolveBatchChain(details, next) : next;
    }

    private static IPatternDetails resolveBatchChain(IPatternDetails first, IPatternDetails current) {
        var visited = new IdentityHashMap<IPatternDetails, Boolean>();
        visited.put(first, Boolean.TRUE);
        while (current instanceof IBatchExecutionPattern wrapped) {
            if (visited.put(current, Boolean.TRUE) != null) {
                throw new IllegalStateException("cyclic batch execution pattern delegation");
            }
            var next = Objects.requireNonNull(
                    wrapped.batchExecutionPattern(), "batchExecutionPattern");
            if (next == current) {
                throw new IllegalStateException("batch execution pattern delegates to itself");
            }
            current = next;
        }
        return current;
    }

    public static IPatternDetails forProviderLookup(IPatternDetails details) {
        Objects.requireNonNull(details, "details");
        if (!(details instanceof IProviderLookupPattern wrapped)) return details;
        var next = Objects.requireNonNull(wrapped.providerLookupPattern(), "providerLookupPattern");
        if (next == details) {
            throw new IllegalStateException("provider lookup pattern delegates to itself");
        }
        return next instanceof IProviderLookupPattern ? resolveProviderChain(details, next) : next;
    }

    private static IPatternDetails resolveProviderChain(IPatternDetails first, IPatternDetails current) {
        var visited = new IdentityHashMap<IPatternDetails, Boolean>();
        visited.put(first, Boolean.TRUE);
        while (current instanceof IProviderLookupPattern wrapped) {
            if (visited.put(current, Boolean.TRUE) != null) {
                throw new IllegalStateException("cyclic provider lookup pattern delegation");
            }
            var next = Objects.requireNonNull(
                    wrapped.providerLookupPattern(), "providerLookupPattern");
            if (next == current) {
                throw new IllegalStateException("provider lookup pattern delegates to itself");
            }
            current = next;
        }
        return current;
    }

    private CraftingPatternDelegates() { }
}
