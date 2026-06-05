package io.github.rrobetti.adaptivebulkhead;

public record BulkheadSnapshot(
        String name,
        int active,
        int effectiveLimit,
        int guaranteedConcurrency,
        int maxConcurrency,
        int borrowedCapacity,
        int lentCapacity,
        long admitted,
        long completed,
        long rejected,
        long executorRejected,
        long cancelled
) {
}
