package com.moakiee.thunderbolt.core.channel;

import java.util.Arrays;

/** Linear-time feasible flow initializer for the bidirectional ME network. */
final class BidirectionalFlowSeed {
    private BidirectionalFlowSeed() {}

    /**
     * Builds a real spanning forest, keeping equal-capacity neighbors together first.
     * No vertex/edge is contracted. A bottom-up pass matches subtree supplies and
     * demands; the top-down pass materializes a capacity-feasible flow on those edges.
     * Non-tree connections remain in the residual graph for the exact completion.
     * Requires fresh node-split graph, with paired physical arcs added consecutively.
     */
    static int assign(ChannelFlowNetwork graph, int s, int t, int[] splits, int limit) {
        int n = splits.length;
        if (n == 0 || limit == 0) return 0;
        int[] head = graph.head, to = graph.to, cap = graph.capacity, nxt = graph.next;
        int[] parent = new int[n];
        int[] order = new int[n];
        int[] parentEdge = new int[n];
        int[] firstChild = new int[n];
        int[] sinkEdge = new int[n];
        int[] matched = new int[n];
        long[] balance = new long[n];
        Arrays.fill(parent, -1);
        Arrays.fill(sinkEdge, -1);
        // Multiple source faces can enter one cable. Supply is summed here;
        // their individual arcs are used when the seed is materialized.
        long[] availableSupply = new long[n];
        for (int e = head[s]; e != -1; e = nxt[e]) {
            if ((e & 1) == 0 && to[e] < n * 2) {
                availableSupply[to[e] / 2] += cap[e];
            }
        }
        for (int e = head[t]; e != -1; e = nxt[e]) {
            if ((e & 1) != 0 && to[e] < n * 2) sinkEdge[to[e] / 2] = e ^ 1;
        }
        // Grow each region before traversing its boundary: a two-level BFS.
        // parent is also the visited map; order always puts parents before children.
        int count = 0;
        int[] boundary = new int[n];
        int bh = 0, bt = 0;
        for (int root = 0; root < n; root++) {
            if (parent[root] != -1) continue;
            parent[root] = root;
            boundary[bt++] = root;
            while (bh < bt) {
                int start = boundary[bh++], lo = count;
                order[count++] = start;
                while (lo < count) {
                    int u = order[lo++];
                    for (int e = head[u * 2 + 1]; e != -1; e = nxt[e]) {
                        int target = to[e];
                        if ((e & 1) != 0 || (target & 1) != 0 || target >= n * 2) continue;
                        int v = target / 2;
                        if (parent[v] != -1 || cap[e] == 0) continue;
                        parent[v] = u;
                        parentEdge[v] = e;
                        if (cap[splits[v]] == cap[splits[u]] && cap[e] >= cap[splits[u]]) {
                            order[count++] = v;
                        } else {
                            boundary[bt++] = v;
                        }
                    }
                }
            }
        }
        // Children contribute only their unmatched export. Matches below the
        // current node are already independent of its remaining capacity.
        long[] availableDemand = new long[n];
        for (int v = 0; v < n; v++) {
            if (sinkEdge[v] >= 0) availableDemand[v] = cap[sinkEdge[v]];
        }
        int total = 0;
        for (int k = n - 1; k >= 0; k--) {
            int v = order[k];
            int c = cap[splits[v]];
            int match = (int) Math.min((long) Math.min(c, limit - total),
                    Math.min(availableSupply[v], availableDemand[v]));
            matched[v] = match;
            total += match;
            long delta = availableSupply[v] - availableDemand[v];
            long export = Math.min(Math.abs(delta), c - match);
            if (parent[v] != v) export = Math.min(export, cap[parentEdge[v]]);
            balance[v] = delta >= 0 ? export : -export;
            if (parent[v] != v) {
                if (delta >= 0) availableSupply[parent[v]] += export;
                else availableDemand[parent[v]] += export;
            }
        }
        // Make child lists using arrays whose bottom-up work has finished.
        Arrays.fill(firstChild, -1);
        for (int k = n - 1; k >= 0; k--) {
            int v = order[k];
            if (parent[v] == v) { balance[v] = 0; continue; }
            boundary[v] = firstChild[parent[v]]; // reused as next sibling
            firstChild[parent[v]] = v;
        }
        for (int k = 0; k < n; k++) {
            int v = order[k];
            long export = balance[v];
            int input = matched[v] + (int) Math.max(0, export);
            int output = matched[v] + (int) Math.max(0, -export);
            // Local supply first. The reverse arcs at v_in enumerate every source face.
            for (int e = head[v * 2]; e != -1 && input > 0; e = nxt[e]) {
                if (to[e] == s && (e & 1) != 0) {
                    int d = Math.min(input, cap[e ^ 1]);
                    graph.push(e ^ 1, d); input -= d;
                }
            }
            if (sinkEdge[v] >= 0) {
                int d = Math.min(output, cap[sinkEdge[v]]);
                graph.push(sinkEdge[v], d); output -= d;
            }
            for (int child = firstChild[v]; child != -1; child = boundary[child]) {
                long offer = balance[child];
                int d;
                if (offer >= 0) {
                    d = (int) Math.min(input, offer); input -= d;
                    balance[child] = d;
                    // Opposite physical arc (not the reverse residual arc).
                    // Connection pairs start immediately after the n split-edge pairs.
                    graph.push(((parentEdge[child] - 2 * n) ^ 2) + 2 * n, d);
                } else {
                    d = (int) Math.min(output, -offer); output -= d;
                    balance[child] = -d;
                    graph.push(parentEdge[child], d);
                }
            }
            if (input != 0 || output != 0) throw new IllegalStateException("infeasible tree seed");
            graph.push(splits[v], matched[v] + (int) Math.abs(export));
        }
        return total;
    }

}
