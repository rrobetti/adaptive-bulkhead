package io.github.rrobetti.adaptivebulkhead;

import java.util.Objects;

public record BulkheadPolicy(
        int guaranteedConcurrency,
        int maxConcurrency,
        int maximumBorrow,
        int minimumRetainedCapacity,
        int weight,
        Priority priority,
        BorrowDirection borrowDirection
) {
    public BulkheadPolicy(
            int guaranteedConcurrency,
            int maxConcurrency,
            int maximumBorrow,
            int minimumRetainedCapacity,
            int weight,
            Priority priority
    ) {
        this(
                guaranteedConcurrency,
                maxConcurrency,
                maximumBorrow,
                minimumRetainedCapacity,
                weight,
                priority,
                BorrowDirection.HIGHER_PRIORITY_ONLY
        );
    }

    public BulkheadPolicy {
        if (guaranteedConcurrency < 0) {
            throw new IllegalArgumentException("guaranteedConcurrency must be non-negative");
        }
        if (maxConcurrency < 0) {
            throw new IllegalArgumentException("maxConcurrency must be non-negative");
        }
        if (maximumBorrow < 0) {
            throw new IllegalArgumentException("maximumBorrow must be non-negative");
        }
        if (minimumRetainedCapacity < 0) {
            throw new IllegalArgumentException("minimumRetainedCapacity must be non-negative");
        }
        if (maxConcurrency < guaranteedConcurrency) {
            throw new IllegalArgumentException("maxConcurrency must be >= guaranteedConcurrency");
        }
        if (maximumBorrow > maxConcurrency - guaranteedConcurrency) {
            throw new IllegalArgumentException("maximumBorrow must be <= maxConcurrency - guaranteedConcurrency");
        }
        if (minimumRetainedCapacity > guaranteedConcurrency) {
            throw new IllegalArgumentException("minimumRetainedCapacity must be <= guaranteedConcurrency");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1");
        }
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(borrowDirection, "borrowDirection");
    }

    public BorrowPolicy borrowPolicy() {
        return new BorrowPolicy(maximumBorrow > 0, maximumBorrow, minimumRetainedCapacity, borrowDirection);
    }
}
