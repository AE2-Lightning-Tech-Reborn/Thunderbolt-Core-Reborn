package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PlannerTopologyGraphTest {
    @Test void duplicateEdgesAndBranchesKeepTheirIndegreesAcrossRepeatedQueries() {
        var graph = new PlannerTopology.MaterialGraph<Integer>();
        var root = graph.item(0);
        var left = graph.item(1);
        var right = graph.item(2);
        var sink = graph.item(3);
        graph.edge(root, left);
        graph.edge(root, left);
        graph.edge(root, right);
        graph.edge(left, sink);
        graph.edge(right, sink);
        assertTrue(graph.isAcyclic());
        assertTrue(graph.isAcyclic());
        assertEquals(4L, graph.components().values().stream().distinct().count());

        graph.edge(sink, root);
        assertFalse(graph.isAcyclic());
        assertEquals(1L, graph.components().values().stream().distinct().count());
    }

    @Test void cyclesAndComponentsAgreeWithIndependentTransitiveClosure() {
        var random = new Random(2026110201L);
        for (int sample = 0; sample < 256; sample++) {
            int size = 1 + random.nextInt(14);
            var graph = new PlannerTopology.MaterialGraph<Integer>();
            var nodes = new PlannerTopology.Vertex[size];
            for (int i = 0; i < size; i++) nodes[i] = graph.item(i);
            boolean[][] reaches = new boolean[size][size];
            for (int from = 0; from < size; from++) for (int to = 0; to < size; to++) {
                if (random.nextInt(8) != 0) continue;
                graph.edge(nodes[from], nodes[to]);
                if (random.nextBoolean()) graph.edge(nodes[from], nodes[to]);
                reaches[from][to] = true;
            }
            for (int via = 0; via < size; via++) for (int from = 0; from < size; from++)
                for (int to = 0; to < size; to++) reaches[from][to] |= reaches[from][via] && reaches[via][to];
            boolean cyclic = false;
            for (int i = 0; i < size; i++) cyclic |= reaches[i][i];
            assertEquals(!cyclic, graph.isAcyclic(), "sample=" + sample);
            var components = graph.components();
            for (int a = 0; a < size; a++) for (int b = 0; b < size; b++)
                assertEquals(a == b || reaches[a][b] && reaches[b][a],
                        components.get(nodes[a]).equals(components.get(nodes[b])), "sample=" + sample);
        }
    }
}
