package io.github.rrobetti.adaptivebulkhead;

public enum RejectionReason {
    CONCURRENCY_LIMIT_REACHED,
    PARENT_BULKHEAD_REJECTED,
    BORROW_LIMIT_REACHED,
    BULKHEAD_DISABLED,
    EXECUTOR_REJECTED,
    CANCELLED_BEFORE_EXECUTION
}
