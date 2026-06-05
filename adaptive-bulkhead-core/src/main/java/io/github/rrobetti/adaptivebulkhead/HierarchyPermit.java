package io.github.rrobetti.adaptivebulkhead;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HierarchyPermit implements Permit {
    private final String bulkheadName;
    private final List<BulkheadNode> path;
    private final BulkheadTree owner;
    private final AtomicBoolean released = new AtomicBoolean();

    HierarchyPermit(String bulkheadName, List<BulkheadNode> path, BulkheadTree owner) {
        this.bulkheadName = bulkheadName;
        this.path = List.copyOf(path);
        this.owner = owner;
    }

    List<BulkheadNode> path() {
        return path;
    }

    boolean release(ReleaseOutcome outcome, boolean failOnDoubleRelease) {
        if (!released.compareAndSet(false, true)) {
            if (failOnDoubleRelease) {
                throw new IllegalStateException("Permit for '" + bulkheadName + "' has already been released");
            }
            return false;
        }
        owner.release(this, outcome);
        return true;
    }

    @Override
    public String bulkheadName() {
        return bulkheadName;
    }

    @Override
    public boolean isReleased() {
        return released.get();
    }

    @Override
    public void close() {
        release(ReleaseOutcome.COMPLETED, true);
    }
}
