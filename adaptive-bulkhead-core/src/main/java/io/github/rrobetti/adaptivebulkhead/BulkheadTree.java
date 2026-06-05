package io.github.rrobetti.adaptivebulkhead;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class BulkheadTree {
    private final BulkheadNode root;
    private final Map<String, BulkheadNode> lookup;
    private final BulkheadEventListener listener;
    private final ReentrantLock lock = new ReentrantLock();

    public BulkheadTree(BulkheadDefinition definition) {
        this(definition, BulkheadEventListener.noop());
    }

    public BulkheadTree(BulkheadDefinition definition, BulkheadEventListener listener) {
        Objects.requireNonNull(definition, "definition");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.lookup = new LinkedHashMap<>();
        this.root = build(definition, null, true);
        if (root.policy().maxConcurrency() <= 0) {
            throw new IllegalArgumentException("root maximum concurrency must be positive");
        }
        root.runtimeState().effectiveLimit.set(root.policy().maxConcurrency());
        root.runtimeState().borrowedCapacity.set(0);
        root.runtimeState().lentCapacity.set(0);
        indexNodes();
        refreshLimits(null, List.of());
    }

    public Optional<Permit> tryAcquire(String bulkheadName) {
        try {
            return Optional.of(acquireOrThrow(bulkheadName));
        } catch (BulkheadRejectedException exception) {
            return Optional.empty();
        }
    }

    public HierarchyPermit acquireOrThrow(String bulkheadName) {
        BulkheadNode requested = resolve(bulkheadName);
        List<BulkheadNode> path = requested.pathFromRoot();
        List<Runnable> notifications = new ArrayList<>();
        lock.lock();
        try {
            refreshLimits(requested, notifications);
            List<BulkheadNode> acquired = new ArrayList<>();
            for (BulkheadNode node : path) {
                if (!tryIncrementWithinLimit(node.runtimeState().active, node.runtimeState().effectiveLimit.get())) {
                    rollback(acquired);
                    node.runtimeState().rejected.increment();
                    refreshLimits(null, notifications);
                    BulkheadRejectedException exception = rejectionFor(bulkheadName, requested, node);
                    notifications.add(() -> safeListenerCall(() -> listener.onRejected(eventFor(bulkheadName, node, exception.reason()))));
                    throw exception;
                }
                acquired.add(node);
            }
            for (BulkheadNode node : path) {
                node.runtimeState().admitted.increment();
            }
            refreshLimits(null, notifications);
            HierarchyPermit permit = new HierarchyPermit(requested.qualifiedName(), path, this);
            notifications.add(() -> safeListenerCall(() -> listener.onAdmitted(eventFor(requested.qualifiedName(), requested, null))));
            return permit;
        } finally {
            lock.unlock();
            notifications.forEach(Runnable::run);
        }
    }

    public BulkheadSnapshot snapshot(String bulkheadName) {
        return resolve(bulkheadName).snapshot();
    }

    public Collection<BulkheadSnapshot> snapshots() {
        List<BulkheadSnapshot> snapshots = new ArrayList<>();
        for (BulkheadNode node : new HashSet<>(lookup.values())) {
            snapshots.add(node.snapshot());
        }
        snapshots.sort((left, right) -> left.name().compareTo(right.name()));
        return Collections.unmodifiableList(snapshots);
    }

    public void emitStarted(String bulkheadName) {
        BulkheadNode node = resolve(bulkheadName);
        safeListenerCall(() -> listener.onStarted(eventFor(node.qualifiedName(), node, null)));
    }

    void release(HierarchyPermit permit, ReleaseOutcome outcome) {
        List<Runnable> notifications = new ArrayList<>();
        lock.lock();
        try {
            List<BulkheadNode> path = permit.path();
            for (int index = path.size() - 1; index >= 0; index--) {
                BulkheadNode node = path.get(index);
                decrement(node.runtimeState().active);
                switch (outcome) {
                    case COMPLETED -> node.runtimeState().completed.increment();
                    case EXECUTOR_REJECTED -> node.runtimeState().executorRejected.increment();
                    case CANCELLED -> node.runtimeState().cancelled.increment();
                }
            }
            refreshLimits(null, notifications);
            BulkheadNode node = path.get(path.size() - 1);
            notifications.add(() -> safeListenerCall(() -> listener.onCompleted(eventFor(permit.bulkheadName(), node, null))));
        } finally {
            lock.unlock();
            notifications.forEach(Runnable::run);
        }
    }

    private BulkheadNode build(BulkheadDefinition definition, BulkheadNode parent, boolean rootNode) {
        if (!rootNode && definition.policy().maxConcurrency() > parent.policy().maxConcurrency()) {
            throw new IllegalArgumentException("child maxConcurrency may not exceed parent maxConcurrency");
        }
        String qualifiedName;
        if (parent == null) {
            qualifiedName = definition.name();
        } else if (parent.parent() == null) {
            qualifiedName = definition.name();
        } else {
            qualifiedName = parent.qualifiedName() + "/" + definition.name();
        }
        BulkheadNode node = new BulkheadNode(definition.name(), qualifiedName, definition.policy(), parent);
        Set<String> names = new HashSet<>();
        for (BulkheadDefinition child : definition.children()) {
            if (!names.add(child.name())) {
                throw new IllegalArgumentException("duplicate child name '" + child.name() + "' under '" + node.qualifiedName() + "'");
            }
            node.addChild(build(child, node, false));
        }
        return node;
    }

    private void indexNodes() {
        Map<String, List<BulkheadNode>> byLocalName = new HashMap<>();
        visit(root, node -> {
            lookup.put(node.qualifiedName(), node);
            if (node != root) {
                lookup.put(root.name() + "/" + node.qualifiedName(), node);
            }
            byLocalName.computeIfAbsent(node.name(), key -> new ArrayList<>()).add(node);
        });
        byLocalName.forEach((name, nodes) -> {
            if (nodes.size() == 1) {
                lookup.putIfAbsent(name, nodes.getFirst());
            }
        });
    }

    private void visit(BulkheadNode node, java.util.function.Consumer<BulkheadNode> visitor) {
        visitor.accept(node);
        for (BulkheadNode child : node.children()) {
            visit(child, visitor);
        }
    }

    private BulkheadNode resolve(String bulkheadName) {
        if (bulkheadName == null || bulkheadName.isBlank()) {
            throw new IllegalArgumentException("bulkheadName must not be blank");
        }
        BulkheadNode node = lookup.get(bulkheadName);
        if (node == null) {
            throw new IllegalArgumentException("Unknown bulkhead '" + bulkheadName + "'");
        }
        return node;
    }

    private void rollback(List<BulkheadNode> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            decrement(acquired.get(index).runtimeState().active);
        }
    }

    private BulkheadRejectedException rejectionFor(String requestedName, BulkheadNode requested, BulkheadNode rejecting) {
        if (rejecting != requested) {
            return new BulkheadRejectedException(requestedName, rejecting.qualifiedName(), RejectionReason.PARENT_BULKHEAD_REJECTED);
        }
        RejectionReason reason = rejecting.runtimeState().effectiveLimit.get() < rejecting.policy().maxConcurrency()
                ? RejectionReason.BORROW_LIMIT_REACHED
                : RejectionReason.CONCURRENCY_LIMIT_REACHED;
        return new BulkheadRejectedException(requestedName, rejecting.qualifiedName(), reason);
    }

    private void refreshLimits(BulkheadNode requested, List<Runnable> notifications) {
        root.runtimeState().effectiveLimit.set(root.policy().maxConcurrency());
        root.runtimeState().borrowedCapacity.set(0);
        root.runtimeState().lentCapacity.set(0);
        Map<BulkheadNode, BulkheadNode> requestedChildren = new HashMap<>();
        if (requested != null) {
            List<BulkheadNode> path = requested.pathFromRoot();
            for (int index = 0; index < path.size() - 1; index++) {
                requestedChildren.put(path.get(index), path.get(index + 1));
            }
        }
        refreshChildLimits(root, requested, requestedChildren, notifications);
    }

    private void refreshChildLimits(
            BulkheadNode parent,
            BulkheadNode requested,
            Map<BulkheadNode, BulkheadNode> requestedChildren,
            List<Runnable> notifications
    ) {
        Map<BulkheadNode, BorrowingAllocator.Allocation> allocations = BorrowingAllocator.allocate(
                parent,
                requestedChildren.get(parent),
                parent == requested
        );
        for (BulkheadNode child : parent.children()) {
            BorrowingAllocator.Allocation allocation = allocations.get(child);
            if (allocation == null) {
                continue;
            }
            applyAllocation(child, allocation, requested == null ? child.qualifiedName() : requested.qualifiedName(), notifications);
            refreshChildLimits(child, requested, requestedChildren, notifications);
        }
    }

    private void applyAllocation(
            BulkheadNode node,
            BorrowingAllocator.Allocation allocation,
            String requestedName,
            List<Runnable> notifications
    ) {
        BulkheadRuntimeState state = node.runtimeState();
        int previousLimit = state.effectiveLimit.getAndSet(allocation.effectiveLimit());
        int previousBorrowed = state.borrowedCapacity.getAndSet(allocation.borrowedCapacity());
        int previousLent = state.lentCapacity.getAndSet(allocation.lentCapacity());
        if (previousLimit != allocation.effectiveLimit()
                || previousBorrowed != allocation.borrowedCapacity()
                || previousLent != allocation.lentCapacity()) {
            BulkheadEvent event = eventFor(requestedName, node, null);
            notifications.add(() -> safeListenerCall(() -> listener.onCapacityChanged(event)));
        }
    }

    private BulkheadEvent eventFor(String requestedName, BulkheadNode node, RejectionReason reason) {
        return new BulkheadEvent(Instant.now(), requestedName, node.qualifiedName(), reason, node.snapshot());
    }

    private void safeListenerCall(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ignored) {
            // Listener failures must not affect admission or release.
        }
    }

    private static boolean tryIncrementWithinLimit(java.util.concurrent.atomic.AtomicInteger counter, int limit) {
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static void decrement(java.util.concurrent.atomic.AtomicInteger counter) {
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                throw new IllegalStateException("Bulkhead counter underflow detected");
            }
            if (counter.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }
}
