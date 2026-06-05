package io.github.rrobetti.adaptivebulkhead;

import java.time.Instant;

public record BulkheadEvent(
        Instant timestamp,
        String requestedBulkhead,
        String bulkheadName,
        RejectionReason rejectionReason,
        BulkheadSnapshot snapshot
) {
}
