package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Identity-based candidate queue with O(1) removal and success affinity. */
final class IdentityCandidateQueue<T> implements Iterable<T> {
    private final IdentityHashMap<T, Node<T>> active = new IdentityHashMap<>();
    private IdentityHashMap<T, Node<T>> blocked;
    private ArrayList<Node<T>> blockedNodes;
    private Node<T> head;
    private Node<T> tail;
    private Node<T> preferred;

    IdentityCandidateQueue(Iterable<? extends T> candidates) {
        for (var candidate : candidates) {
            if (candidate == null || active.containsKey(candidate)) continue;
            var node = new Node<>(candidate);
            active.put(candidate, node);
            if (tail == null) {
                head = tail = node;
            } else {
                tail.next = node;
                node.previous = tail;
                tail = node;
            }
        }
    }

    void block(T candidate) {
        var node = active.remove(candidate);
        if (blocked == null) {
            blocked = new IdentityHashMap<>();
            blockedNodes = new ArrayList<>();
        } else if (node == null && blocked.containsKey(candidate)) return;
        blocked.put(candidate, node);
        if (node != null) {
            blockedNodes.add(node);
            if (node.previous != null) node.previous.next = node.next;
            else head = node.next;
            if (node.next != null) node.next.previous = node.previous;
            else tail = node.previous;
            // Keep next intact so in-flight iterators can walk past removed nodes. The unused
            // predecessor marks inactivity without a per-node hash lookup or an extra field.
            node.previous = node;
        }
        if (node != null && preferred == node) preferred = null;
    }

    void markSuccess(T candidate) {
        var node = active.get(candidate);
        if (node != null) preferred = node;
    }

    boolean isBlocked(T candidate) {
        return blocked != null && blocked.containsKey(candidate);
    }

    int activeCount() {
        return active.size();
    }

    int blockedCount() {
        return blocked == null ? 0 : blocked.size();
    }

    /** Re-enable only last tick's rejected candidates; stable active nodes are never rebuilt. */
    void restoreBlocked() {
        if (blocked == null || blocked.isEmpty()) return;
        for (var node : blockedNodes) {
            node.previous = tail;
            node.next = null;
            if (tail == null) head = node;
            else tail.next = node;
            tail = node;
            active.put(node.value, node);
        }
        blocked.clear();
        blockedNodes.clear();
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private final Node<T> preferredFirst = preferred;
            private boolean preferredVisited;
            private Node<T> cursor = head;
            private T next;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (!preferredVisited) {
                        preferredVisited = true;
                        if (preferredFirst != null && preferredFirst.isActive()) {
                            next = preferredFirst.value;
                            break;
                        }
                    }
                    if (cursor == null) break;
                    var node = cursor;
                    cursor = cursor.next;
                    if (node != preferredFirst && node.isActive()) {
                        next = node.value;
                    }
                }
                return next != null;
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                var result = next;
                next = null;
                return result;
            }
        };
    }

    private static final class Node<T> {
        private final T value;
        private Node<T> previous;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }

        private boolean isActive() {
            return previous != this;
        }
    }
}
