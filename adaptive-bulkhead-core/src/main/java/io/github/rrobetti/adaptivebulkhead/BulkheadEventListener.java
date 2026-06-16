package io.github.rrobetti.adaptivebulkhead;

public interface BulkheadEventListener {

    BulkheadEventListener NO_OP = new BulkheadEventListener() {
    };

    default void onAdmitted(BulkheadEvent event) {
    }

    default void onRejected(BulkheadEvent event) {
    }

    default void onStarted(BulkheadEvent event) {
    }

    default void onCompleted(BulkheadEvent event) {
    }

    default void onCapacityChanged(BulkheadEvent event) {
    }

    static BulkheadEventListener noop() {
        return NO_OP;
    }
}
