package io.github.rrobetti.adaptivebulkhead;

/**
 * Controls which sibling lanes may lend capacity to a borrower.
 */
public enum BorrowDirection {
    HIGHER_PRIORITY_ONLY,
    ANY
}
