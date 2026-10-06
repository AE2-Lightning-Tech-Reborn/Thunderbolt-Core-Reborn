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
     * Requires fresh node-split graph, with paired physical arcs added consecutively,
     * followed by source and sink arcs.
     */
    static int assign(ChannelFlowNetwork graph, int s, int t, int[] splits, int limit) {
        int n = splits.length;
        if (n == 0 || limit == 0 || graph.head[s] < 0 || graph.head[t] < 0) return 0;
        int[] head = graph.head, to = graph.to, cap = graph.capacity, nxt = graph.next;
        int[] parent = new int[n];
        int[] order = new int[n];
        int[] parentEdge = new int[n];
        int[] matched = new int[n];
        Arrays.fill(parent, -1);
        // Multiple source faces can enter one cable. Supply is summed here;
        // their individual arcs are used when the seed is materialized.
        long[] availableSupply = new long[n];
        for (int e = head[s]; e != -1; e = nxt[e]) {
            if ((e & 1) == 0 && to[e] < n * 2) {
                availableSupply[to[e] / 2] += cap[e];
            }
        }
        long[] availableDemand = new long[n];
        for (int e = head[t]; e != -1; e = nxt[e]) {
            if ((e & 1) != 0 && to[e] < n * 2) {
                availableDemand[to[e] / 2] += cap[e ^ 1];
            }
        }
        // Grow each region before traversing its boundary: a two-level BFS.
        // parent is also the visited map; order always puts parents before children.
        int count = 0;
        // The residual BFS queue is idle until the seed has been materialized.
        // It is large enough for both the frontier and subsequent sibling links.
        int[] boundary = graph.queue;
        int bh = 0, bt = 0;
        for (int root = 0; root < n; root++) {
            if (parent[root] != -1) continue;
            parent[root] = root;
            parentEdge[root] = -1;
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
            int edge = parentEdge[v];
            if (edge >= 0) export = Math.min(export, cap[edge]);
            // This node's supply summary is no longer needed after its export
            // reaches the parent. Reuse the slot as its signed tree balance.
            availableSupply[v] = delta >= 0 ? export : -export;
            if (edge >= 0) {
                if (delta >= 0) availableSupply[parent[v]] += export;
                else availableDemand[parent[v]] += export;
            }
        }
        if (total == 0) return 0; // No matched paths to materialize; completion starts fresh.
        long[] balance = availableSupply;
        // Make child lists using arrays whose bottom-up work has finished.
        // Parent nodes are no longer needed; parentEdge retains that relation.
        int[] firstChild = parent;
        Arrays.fill(firstChild, -1);
        for (int k = n - 1; k >= 0; k--) {
            int v = order[k];
            int edge = parentEdge[v];
            if (edge < 0) { balance[v] = 0; continue; }
            int parentNode = to[edge ^ 1] / 2;
            boundary[v] = firstChild[parentNode]; // reused as next sibling
            firstChild[parentNode] = v;
        }
        for (int k = 0; k < n; k++) {
            int v = order[k];
            long export = balance[v];
            int input = matched[v] + (int) Math.max(0, export);
            int output = matched[v] + (int) Math.max(0, -export);
            // Source arcs were appended after physical links, so their reverse
            // arcs form the prefix at v_in. Relays with no local supply skip
            // this scan entirely, rather than walking all incoming physical arcs.
            for (int e = head[v * 2]; e != -1 && input > 0 && to[e] == s; e = nxt[e]) {
                int d = Math.min(input, cap[e ^ 1]);
                graph.push(e ^ 1, d); input -= d;
            }
            // Demand arcs are likewise a prefix at v_out. Consume each local
            // request before exporting demand to the children.
            for (int e = head[v * 2 + 1]; e != -1 && output > 0 && to[e] == t; e = nxt[e]) {
                int d = Math.min(output, cap[e]);
                graph.push(e, d); output -= d;
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
