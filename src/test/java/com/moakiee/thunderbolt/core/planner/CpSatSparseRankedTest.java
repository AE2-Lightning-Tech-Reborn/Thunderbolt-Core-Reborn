package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CpSatSparseRankedTest {
    @Test void oneSpecialRecipeDoesNotRequireADenseThousandRecipeGraph() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        long n = 11;
        for (String kind : List.of("side","container","finite","catalyst","private","cycle","feedback")) {
            var b = CraftGraph.<String>builder().stock("raw",n);
            switch (kind) {
                case "side" -> b.pattern(new CraftPattern<>("P0",1,List.of(CraftInput.of("raw",1)),List.of(CraftOutput.of("side",1)),null));
                case "container" -> b.pattern("P0",1,List.of(CraftInput.consumedReturning("raw",1,"empty")));
                case "finite" -> b.pattern("P0",1,List.of(CraftInput.of("raw",1),CraftInput.finiteUse("tool",1,10))).stock("tool",2);
                case "catalyst" -> b.pattern("P0",1,List.of(CraftInput.of("raw",1),CraftInput.returned("tool",1))).stock("tool",1);
                case "private" -> b.pattern("P0",1,List.of(CraftInput.of("raw",1),CraftInput.returnedFrom("seed",1,new ReusableStockSource("host","pool")))).reusableStock("host","seed",1);
                case "cycle" -> b.pattern("A",1,List.of(CraftInput.of("B",1))).pattern("B",1,List.of(CraftInput.of("A",1)))
                        .pattern("P0",1,List.of(CraftInput.of("B",1))).stock("A",n);
                case "feedback" -> b.pattern("B",1,List.of(CraftInput.of("A",1),CraftInput.of("raw",1)))
                        .pattern(new CraftPattern<>("P0",1,List.of(CraftInput.of("B",1)),List.of(CraftOutput.of("A",1)),null)).stock("A",1);
            }
            for (int i = 1; i < 1000; i++) b.pattern("P"+i,1,List.of(CraftInput.of("P"+(i-1),1)));
            var graph = b.build();
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> CpSatRankedFlowSolver.solve(graph,"P999",n));
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED,result.status(),kind);
            assertTrue(result.plan().feasible(),kind);
            assertEquals(n,result.plan().firings().get(graph.patternsFor("P999").get(0)),kind);
            if (!kind.equals("cycle")) assertEquals(n,result.plan().usedStock().get("raw"),kind);
            if (kind.equals("finite")) assertEquals(2L,result.plan().usedStock().get("tool"));
            if (kind.equals("private")) assertEquals(1L,result.plan().usedReusableStock().values().stream().mapToLong(Long::longValue).sum());
        }
    }
}
