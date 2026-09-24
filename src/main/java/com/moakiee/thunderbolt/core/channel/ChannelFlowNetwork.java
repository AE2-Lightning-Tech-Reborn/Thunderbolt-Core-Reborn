package com.moakiee.thunderbolt.core.channel;

import java.util.Arrays;

/**
 * Integer residual network. Starts from a feasible flow and completes it exactly.
 * A bounded number of spanning-tree augmentations batches demands at different
 * depths. Hard residuals use highest-label push/relabel, including returning all
 * unused excess to the source. Neither phase uses the Java call stack for paths.
 */
final class ChannelFlowNetwork {
    private static final int TREE_PASSES = 4;

    private final int size;
    // Package access is limited to the bidirectional initializer; edge ids stay stable.
    final int[] head;
    int[] to, capacity, next;
    private int edgeCount;
    private final int[] queue;

    // Allocated only when the linear passes leave a difficult residual network.
    private int[] height, current, heightCount, bucket, nextActive;
    private long[] excess;
    private boolean[] active;
    private int source, sink, highest;
    private long work, relabelThreshold;

    ChannelFlowNetwork(int vertices) {
        size = vertices + 1; // Reserved source for a bounded residual flow.
        head = new int[size];
        Arrays.fill(head, -1);
        int initial = Math.max(size * 6, 64);
        to = new int[initial];
        capacity = new int[initial];
        next = new int[initial];
        queue = new int[size];
    }

    int edgeCount() {
        return edgeCount;
    }

    int residual(int edge) {
        return capacity[edge];
    }

    void addEdge(int from, int target, int cap) {
        if (cap < 0) throw new IllegalArgumentException("Negative channel capacity");
        if (edgeCount + 2 > to.length) {
            int length = Math.max(to.length * 2, edgeCount + 2);
            to = Arrays.copyOf(to, length);
            capacity = Arrays.copyOf(capacity, length);
            next = Arrays.copyOf(next, length);
        }
        link(from, target, cap);
        link(target, from, 0);
    }

    private void link(int from, int target, int cap) {
        to[edgeCount] = target;
        capacity[edgeCount] = cap;
        next[edgeCount] = head[from];
        head[from] = edgeCount++;
    }

    void push(int edge, int amount) {
        capacity[edge] -= amount;
        capacity[edge ^ 1] += amount;
    }

    void cancelFlow(int edge, int amount) {
        if (amount < 0 || capacity[edge ^ 1] < amount) {
            throw new IllegalStateException("Invalid channel circulation");
        }
        push(edge ^ 1, amount);
    }

    /** Additional flow only; existing feasible flow is retained and may be rerouted. */
    int maxFlow(int s, int t, int limit) {
        long supply = 0, demand = 0;
        for (int e = head[s]; e != -1; e = next[e]) supply += capacity[e];
        for (int e = head[t]; e != -1; e = next[e]) demand += capacity[e ^ 1];
        limit = (int) Math.min(limit, Math.min(supply, demand));
        if (limit <= 0) return 0;

        int[] parent = new int[size];
        int[] children = new int[size];
        int[] sibling = new int[size];
        int[] amount = new int[size];
        int flow = 0;
        // Each pass scans O(V+E). The fixed work budget prevents path-length or
        // channel-count dependent numbers of whole-network searches.
        for (int pass = 0; pass < TREE_PASSES && flow < limit; pass++) {
            Arrays.fill(parent, -1);
            Arrays.fill(children, -1);
            Arrays.fill(amount, 0);
            int lo = 0, hi = 0;
            queue[hi++] = s;
            parent[s] = -2;
            while (lo < hi) {
                int u = queue[lo++];
                for (int e = head[u]; e != -1; e = next[e]) {
                    int v = to[e];
                    if (capacity[e] <= 0) continue;
                    if (v == t) {
                        amount[u] = (int) Math.min((long) limit - flow, (long) amount[u] + capacity[e]);
                    } else if (parent[v] == -1) {
                        parent[v] = e;
                        sibling[v] = children[u];
                        children[u] = v;
                        queue[hi++] = v;
                    }
                }
            }
            for (int k = hi - 1; k > 0; k--) {
                int v = queue[k], e = parent[v], u = to[e ^ 1];
                amount[u] = (int) Math.min((long) limit - flow,
                        (long) amount[u] + Math.min(capacity[e], amount[v]));
            }
            int sent = amount[s];
            if (sent == 0) return flow; // No residual s-t path: a maximum-flow certificate.
            for (int k = 0; k < hi; k++) {
                int u = queue[k], remaining = amount[u];
                for (int e = head[u]; e != -1 && remaining > 0; e = next[e]) {
                    if (to[e] == t && capacity[e] > 0) {
                        int d = Math.min(remaining, capacity[e]);
                        push(e, d);
                        remaining -= d;
                    }
                }
                for (int v = children[u]; v != -1; v = sibling[v]) {
                    int e = parent[v], d = Math.min(remaining, Math.min(capacity[e], amount[v]));
                    amount[v] = d;
                    push(e, d);
                    remaining -= d;
                }
                if (remaining != 0) throw new IllegalStateException("Infeasible channel tree flow");
            }
            flow += sent;
        }
        return flow < limit ? flow + pushRemaining(s, t, limit - flow) : flow;
    }

    /** Package-visible so tests can also check the completion kernel independently. */
    int pushRemaining(int s, int t, int limit) {
        if (limit <= 0) return 0;
        height = new int[size];
        current = new int[size];
        heightCount = new int[2 * size + 2];
        bucket = new int[2 * size + 2];
        nextActive = new int[size];
        excess = new long[size];
        active = new boolean[size];
        sink = t;
        source = size - 1;
        // An artificial source bounds *additional* flow even when the existing
        // source already carries the bidirectional seed. Every internal excess
        // (including s) must be zero before the flow is returned to AE2.
        addEdge(source, s, limit);
        relabelThreshold = Math.max(1L, 4L * edgeCount + size);
        for (int e = head[source]; e != -1; e = next[e]) {
            int d = capacity[e];
            push(e, d);
            excess[to[e]] += d;
            excess[source] -= d;
        }
        globalRelabel();
        while (true) {
            while (highest >= 0 && bucket[highest] == -1) highest--;
            if (highest < 0) break;
            int v = bucket[highest];
            bucket[highest] = nextActive[v];
            active[v] = false;
            if (excess[v] > 0) {
                discharge(v);
                activate(v);
            }
        }
        for (int v = 0; v < size; v++) {
            if (v != source && v != sink && excess[v] != 0) {
                throw new IllegalStateException("Unfinished channel preflow");
            }
        }
        return Math.toIntExact(excess[sink]);
    }

    private void activate(int v) {
        if (v == source || v == sink || excess[v] <= 0 || active[v]) return;
        if (height[v] >= 2 * size) throw new IllegalStateException("Stranded channel excess");
        active[v] = true;
        nextActive[v] = bucket[height[v]];
        bucket[height[v]] = v;
        highest = Math.max(highest, height[v]);
    }

    private void globalRelabel() {
        Arrays.fill(height, 2 * size);
        reverseDistances(sink, 0);
        // Vertices unable to reach the sink still need valid labels to return
        // unused supply. Stopping at a maximum preflow would corrupt node readback.
        reverseDistances(source, size);
        Arrays.fill(bucket, -1);
        Arrays.fill(active, false);
        Arrays.fill(heightCount, 0);
        highest = -1;
        for (int v = 0; v < size; v++) {
            heightCount[height[v]]++;
            current[v] = head[v];
            activate(v);
        }
        work = 0;
    }

    private void reverseDistances(int root, int base) {
        int lo = 0, hi = 0;
        height[root] = base;
        queue[hi++] = root;
        while (lo < hi) {
            int v = queue[lo++];
            for (int e = head[v]; e != -1; e = next[e]) {
                int u = to[e];
                if (u != source && height[u] == 2 * size && capacity[e ^ 1] > 0) {
                    height[u] = height[v] + 1;
                    queue[hi++] = u;
                }
            }
        }
    }

    private void discharge(int u) {
        while (excess[u] > 0) {
            int e = current[u];
            if (e == -1) {
                int old = height[u], best = 2 * size;
                for (int j = head[u]; j != -1; j = next[j]) {
                    work++;
                    if (capacity[j] > 0) best = Math.min(best, height[to[j]] + 1);
                }
                if (best >= 2 * size) throw new IllegalStateException("No return path for channel excess");
                heightCount[old]--;
                height[u] = best;
                heightCount[best]++;
                current[u] = head[u];
                // An empty layer proves higher vertices below n cannot reach t.
                if (old < size && heightCount[old] == 0) {
                    globalRelabel();
                    return;
                }
            } else {
                work++;
                int v = to[e];
                if (capacity[e] > 0 && height[u] == height[v] + 1) {
                    int d = (int) Math.min(excess[u], (long) capacity[e]);
                    push(e, d);
                    excess[u] -= d;
                    excess[v] += d;
                    activate(v);
                } else {
                    current[u] = next[e];
                }
            }
            if (work >= relabelThreshold) {
                globalRelabel();
                return;
            }
        }
    }
}
