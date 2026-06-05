package io.github.rrobetti.adaptivebulkhead;

public record BorrowPolicy(
        boolean enabled,
        int maximumBorrow,
        int minimumRetainedCapacity,
        BorrowDirection direction
) {
    public BorrowPolicy(boolean enabled, int maximumBorrow, int minimumRetainedCapacity) {
        this(enabled, maximumBorrow, minimumRetainedCapacity, BorrowDirection.HIGHER_PRIORITY_ONLY);
    }

    public BorrowPolicy {
        if (maximumBorrow < 0) {
            throw new IllegalArgumentException("maximumBorrow must be non-negative");
        }
        if (minimumRetainedCapacity < 0) {
            throw new IllegalArgumentException("minimumRetainedCapacity must be non-negative");
        }
        if (!enabled && maximumBorrow > 0) {
            throw new IllegalArgumentException("maximumBorrow must be zero when borrowing is disabled");
        }
        java.util.Objects.requireNonNull(direction, "direction");
    }
}
