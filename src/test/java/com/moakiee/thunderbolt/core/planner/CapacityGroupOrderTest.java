package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.*;
import java.lang.reflect.*;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

class CapacityGroupOrderTest {
    @Test void sourceIdentityGroupsAndEveryCapacityTieKeepTheirOrder() throws Exception {
        Class<?> plannerClass = CraftPlannerV2.class;
        Class<?> diagClass = Class.forName(plannerClass.getName() + "$DiagnosticsCollector");
        var diagCtor = diagClass.getDeclaredConstructors()[0]; diagCtor.setAccessible(true);
        var diag = diagCtor.newInstance(1, 1, new CraftPlannerV2.PlanningSession<Integer>());
        var ctor = Arrays.stream(plannerClass.getDeclaredConstructors())
                .filter(c -> c.getParameterTypes()[0] == CraftGraph.class).findFirst().orElseThrow();
        ctor.setAccessible(true);
        var planner = ctor.newInstance(CraftGraph.<Integer>builder().build(), 1, null, null, diag);
        var method = plannerClass.getDeclaredMethod("groupedCapacityOrder", List.class, ToLongFunction.class, ToLongFunction.class);
        method.setAccessible(true);
        var random = new Random(2026100127L);
        long[] values = {0, 1, 2, 7, 11, Long.MAX_VALUE / 2, Long.MAX_VALUE};
        int samples = 2048;
        for (int sample = 0; sample < samples; sample++) {
            var sources = new Object[]{null, new String("equal"), new String("equal"), new Object(), new Object()};
            int size = sample % 65;
            var patterns = new ArrayList<CraftPattern<Integer>>();
            var total = new IdentityHashMap<CraftPattern<Integer>, Long>();
            var direct = new IdentityHashMap<CraftPattern<Integer>, Long>();
            for (int i = 0; i < size; i++) {
                var pattern = i > 0 && random.nextInt(7) == 0 ? patterns.get(random.nextInt(i))
                        : new CraftPattern<>(99, 1, List.of(), sample % 3 == 0 ? new Object() : (sample % 3 == 1 ? null : sources[random.nextInt(sources.length)]));
                patterns.add(pattern);
                total.putIfAbsent(pattern, values[random.nextInt(values.length)]);
                direct.putIfAbsent(pattern, values[random.nextInt(values.length)]);
            }
            int[] group = new int[size]; long[] maximum = new long[size];
            var expected = new ArrayList<Integer>();
            for (int i = 0; i < size; i++) {
                group[i] = i; expected.add(i);
                Object source = patterns.get(i).source();
                if (source != null) for (int prior = 0; prior < i; prior++)
                    if (source == patterns.get(prior).source()) { group[i] = prior; break; }
                maximum[group[i]] = Math.max(maximum[group[i]], total.get(patterns.get(i)));
            }
            expected.sort((a, b) -> {
                if (group[a] != group[b]) {
                    int c = Long.compare(maximum[group[b]], maximum[group[a]]);
                    return c != 0 ? c : Integer.compare(group[a], group[b]);
                }
                int c = Long.compare(direct.get(patterns.get(b)), direct.get(patterns.get(a)));
                if (c == 0) c = Long.compare(total.get(patterns.get(b)), total.get(patterns.get(a)));
                return c != 0 ? c : Integer.compare(a, b);
            });
            var before = List.copyOf(patterns);
            ToLongFunction<CraftPattern<Integer>> capacity = total::get;
            ToLongFunction<CraftPattern<Integer>> stocked = direct::get;
            @SuppressWarnings("unchecked")
            var actual = (List<CraftPattern<Integer>>) method.invoke(planner, patterns, capacity, stocked);
            if (!patterns.equals(before) || actual.size() != size) throw new AssertionError("mutation " + sample);
            for (int i = 0; i < size; i++) {
                if (actual.get(i) != patterns.get(expected.get(i))) throw new AssertionError("order " + sample + "/" + i);
            }
        }
    }
}
