package com.moakiee.thunderbolt.ae2.crafting;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.LoggerFactory;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingPlanSummary;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/** Populates GTLCore's optional row metadata without linking its classes on ordinary Forge. */
final class GtlCraftingPlanSummaryBridge {
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static final ClassValue<Optional<Method>> SETTERS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                var setter = type.getMethod("gtlcore$setCraftTimes", long.class);
                return setter.getReturnType() == void.class ? Optional.of(setter) : Optional.empty();
            } catch (NoSuchMethodException absent) {
                return Optional.empty();
            }
        }
    };

    private GtlCraftingPlanSummaryBridge() {}

    static void attach(CraftingPlanSummary summary, ICraftingPlan plan) {
        var entries = summary.getEntries();
        if (entries.isEmpty() || SETTERS.get(entries.get(0).getClass()).isEmpty()) {
            return;
        }
        var times = new Object2LongOpenHashMap<AEKey>();
        for (var pattern : plan.patternTimes().entrySet()) {
            long copies = pattern.getValue();
            if (copies <= 0) continue;
            // GTL counts executions for every output, independently of its output stack size.
            for (var output : pattern.getKey().getOutputs()) {
                times.mergeLong(output.what(), copies,
                        (left, right) -> left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right);
            }
        }
        try {
            for (var entry : entries) {
                var setter = SETTERS.get(entry.getClass());
                if (setter.isPresent()) setter.get().invoke(entry, times.getLong(entry.getWhat()));
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            if (FAILURE_REPORTED.compareAndSet(false, true)) {
                LoggerFactory.getLogger("thunderbolt").warn(
                        "Could not attach GTLCore crafting counts to a plan summary", failure);
            }
        }
    }
}
