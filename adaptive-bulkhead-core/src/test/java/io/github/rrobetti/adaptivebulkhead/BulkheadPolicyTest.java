package io.github.rrobetti.adaptivebulkhead;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BulkheadPolicyTest {

    @Test
    void rejectsInvalidBorrowConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BulkheadPolicy(2, 3, 2, 3, 1, Priority.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> new BulkheadPolicy(2, 1, 0, 0, 1, Priority.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> new BulkheadPolicy(1, 2, 2, 0, 1, Priority.NORMAL));
    }

    @Test
    void exposesBorrowPolicy() {
        BulkheadPolicy policy = new BulkheadPolicy(4, 8, 3, 2, 5, Priority.HIGH);
        BorrowPolicy borrowPolicy = policy.borrowPolicy();

        assertEquals(new BorrowPolicy(true, 3, 2, BorrowDirection.HIGHER_PRIORITY_ONLY), borrowPolicy);
    }

    @Test
    void defaultsBorrowDirectionToHigherPriorityOnly() {
        BulkheadPolicy policy = new BulkheadPolicy(4, 8, 3, 2, 5, Priority.HIGH);

        assertEquals(BorrowDirection.HIGHER_PRIORITY_ONLY, policy.borrowDirection());
    }

    @Test
    void acceptsExplicitBorrowDirection() {
        BulkheadPolicy policy = new BulkheadPolicy(0, 4, 2, 0, 1, Priority.BACKGROUND, BorrowDirection.ANY);

        assertEquals(BorrowDirection.ANY, policy.borrowDirection());
    }
}
