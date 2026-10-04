package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProducibilityRankedOrientationTest {
    /** Regression reduced from a cycle-cut case where every required item has an input-free route. */
    private static final String[] RECIPES = {
        "1760: 1x1072 <- 5x5754 1x9768 3x9798",
        "1772: 1x1077 <- 1x1078",
        "1778: 1x1078 <- 100x11625 20x11704",
        "4212: 1x2224 <- 1000x11624 1000x11713",
        "4213: 1x2224 <- 1x4614",
        "4230: 3x2229 <- 1x11704",
        "10063: 1x4614 <- 8x12158",
        "12619: 1x5754 <- 1x9325",
        "12623: 5x5754 <- 100x11625 20x11704",
        "12847: 4x5906 <- 1x11704",
        "12848: 1x5907 <- 2x5911",
        "12873: 24x5911 <- 3x5906",
        "18274: 1x8990 <- 1x4359",
        "18275: 8x8990 <- 100x11625 20x11704",
        "18770: 1x9314 <- 1x11704",
        "18778: 1x9317 <- 1x7378 4x9314",
        "19482: 1x9768 <- 1x4614 3x5754 4x8990 1x10834",
        "19542: 1x9798 <- 1x2224",
        "19557: 1x9809 <-",
        "21464: 1x10834 <- 4x5754 5x12098",
        "23041: 250x11624 <- 1x1077",
        "23042: 250x11624 <- 1x6559",
        "23056: 250x11625 <- 1000x11713 1x12199",
        "23209: 1000x11675 <- 1000x11713",
        "23348: 1000x11704 <- 1000x11675",
        "23371: 8000x11713 <- 2x3418 2x3431 1x6458 2x8079 1x9317 8000x11629",
        "23380: 250x11713 <- 1x9809",
        "25547: 1x12098 <- 1x4349",
        "25548: 1x12098 <- 1x5907",
        "27653: 1x12158 <- 1x2224",
        "27930: 1x12199 <- 1x2229",
    };

    @Test
    void cycleCutKeepsAProducibleDirectionForEveryNeededMember() {
        var builder = CraftGraph.<String>builder();
        for (String line : RECIPES) {
            String[] sides = line.split(" <-");
            String[] head = sides[0].split(": |x");
            var inputs = new ArrayList<CraftInput<String>>();
            if (sides.length > 1) {
                for (String input : sides[1].trim().split(" ")) {
                    if (input.isEmpty()) continue;
                    String[] part = input.split("x");
                    inputs.add(CraftInput.of(part[1], Long.parseLong(part[0])));
                }
            }
            builder.pattern(new CraftPattern<>(
                    head[2], Long.parseLong(head[1]), List.copyOf(inputs), head[0]));
        }

        PlanningResult<String> result = CraftPlannerV2.planDetailed(builder.build(), "1072", 1);

        assertTrue(result.plan().feasible(), result::toString);
        assertTrue(result.plan().usedStock().isEmpty());
    }
}
