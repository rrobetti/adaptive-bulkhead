package io.github.rrobetti.adaptivebulkhead;

import java.util.concurrent.RejectedExecutionException;

public final class BulkheadRejectedException extends RejectedExecutionException {
    private final String requestedBulkhead;
    private final String rejectingBulkhead;
    private final RejectionReason reason;

    public BulkheadRejectedException(
            String requestedBulkhead,
            String rejectingBulkhead,
            RejectionReason reason
    ) {
        super("Bulkhead '" + requestedBulkhead + "' rejected by '" + rejectingBulkhead + "' due to " + reason);
        this.requestedBulkhead = requestedBulkhead;
        this.rejectingBulkhead = rejectingBulkhead;
        this.reason = reason;
    }

    public String requestedBulkhead() {
        return requestedBulkhead;
    }

    public String rejectingBulkhead() {
        return rejectingBulkhead;
    }

    public RejectionReason reason() {
        return reason;
    }
}
