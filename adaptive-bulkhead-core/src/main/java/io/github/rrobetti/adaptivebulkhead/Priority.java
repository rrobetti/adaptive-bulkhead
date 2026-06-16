package io.github.rrobetti.adaptivebulkhead;

/**
 * Capacity-allocation metadata. Priority influences borrowing and reclaim decisions only.
 * It does not affect JVM thread scheduling or task execution order.
 */
public enum Priority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}
