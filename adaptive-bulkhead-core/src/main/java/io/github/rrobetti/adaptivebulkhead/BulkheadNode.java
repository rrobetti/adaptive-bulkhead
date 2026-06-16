package io.github.rrobetti.adaptivebulkhead;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BulkheadNode {
    private final String name;
    private final String qualifiedName;
    private final BulkheadPolicy policy;
    private final BulkheadNode parent;
    private final List<BulkheadNode> children;
    private final BulkheadRuntimeState runtimeState = new BulkheadRuntimeState();

    BulkheadNode(String name, String qualifiedName, BulkheadPolicy policy, BulkheadNode parent) {
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.policy = policy;
        this.parent = parent;
        this.children = new ArrayList<>();
    }

    String name() {
        return name;
    }

    String qualifiedName() {
        return qualifiedName;
    }

    BulkheadPolicy policy() {
        return policy;
    }

    BulkheadNode parent() {
        return parent;
    }

    BulkheadRuntimeState runtimeState() {
        return runtimeState;
    }

    void addChild(BulkheadNode child) {
        children.add(child);
    }

    List<BulkheadNode> children() {
        return Collections.unmodifiableList(children);
    }

    List<BulkheadNode> pathFromRoot() {
        List<BulkheadNode> path = new ArrayList<>();
        for (BulkheadNode current = this; current != null; current = current.parent) {
            path.add(current);
        }
        Collections.reverse(path);
        return path;
    }

    int directActive() {
        int childActive = 0;
        for (BulkheadNode child : children) {
            childActive += child.runtimeState.active.get();
        }
        return runtimeState.active.get() - childActive;
    }

    BulkheadSnapshot snapshot() {
        return new BulkheadSnapshot(
                qualifiedName,
                runtimeState.active.get(),
                runtimeState.effectiveLimit.get(),
                policy.guaranteedConcurrency(),
                policy.maxConcurrency(),
                runtimeState.borrowedCapacity.get(),
                runtimeState.lentCapacity.get(),
                runtimeState.admitted.sum(),
                runtimeState.completed.sum(),
                runtimeState.rejected.sum(),
                runtimeState.executorRejected.sum(),
                runtimeState.cancelled.sum()
        );
    }
}
