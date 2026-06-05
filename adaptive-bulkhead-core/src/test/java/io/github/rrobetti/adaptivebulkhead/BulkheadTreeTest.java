package io.github.rrobetti.adaptivebulkhead;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkheadTreeTest {

    @Test
    void admitsUpToLimitAndRestoresOnRelease() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 2, 0, 0, 1, Priority.NORMAL),
                List.of()
        ));

        Permit first = tree.acquireOrThrow("application");
        Permit second = tree.acquireOrThrow("application");
        assertThrows(BulkheadRejectedException.class, () -> tree.acquireOrThrow("application"));

        second.close();
        Permit third = tree.acquireOrThrow("application");
        third.close();
        first.close();

        assertEquals(0, tree.snapshot("application").active());
    }

    @Test
    void partialHierarchyAcquisitionRollsBack() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 3, 0, 0, 1, Priority.NORMAL),
                List.of(new BulkheadDefinition(
                        "payments",
                        new BulkheadPolicy(0, 0, 0, 0, 1, Priority.HIGH),
                        List.of()
                ))
        ));

        assertThrows(BulkheadRejectedException.class, () -> tree.acquireOrThrow("payments"));
        assertEquals(0, tree.snapshot("application").active());
        assertEquals(0, tree.snapshot("payments").active());
    }

    @Test
    void childAcquisitionConsumesParentCapacity() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 1, 0, 0, 1, Priority.NORMAL),
                List.of(new BulkheadDefinition(
                        "critical",
                        new BulkheadPolicy(1, 1, 0, 1, 1, Priority.HIGH),
                        List.of()
                ))
        ));

        Permit permit = tree.acquireOrThrow("critical");
        BulkheadRejectedException exception = assertThrows(BulkheadRejectedException.class, () -> tree.acquireOrThrow("application"));

        assertEquals(RejectionReason.CONCURRENCY_LIMIT_REACHED, exception.reason());
        assertEquals(1, tree.snapshot("application").active());
        assertEquals(1, tree.snapshot("critical").active());
        permit.close();
    }

    @Test
    void borrowingRespectsRetainedCapacity() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 30, 0, 0, 1, Priority.NORMAL),
                List.of(
                        new BulkheadDefinition("critical", new BulkheadPolicy(10, 30, 20, 10, 10, Priority.CRITICAL), List.of()),
                        new BulkheadDefinition("normal", new BulkheadPolicy(20, 20, 0, 5, 5, Priority.NORMAL), List.of())
                )
        ));

        Permit[] permits = new Permit[25];
        for (int i = 0; i < permits.length; i++) {
            permits[i] = tree.acquireOrThrow("critical");
        }
        BulkheadRejectedException exception = assertThrows(BulkheadRejectedException.class, () -> tree.acquireOrThrow("critical"));

        assertEquals(RejectionReason.BORROW_LIMIT_REACHED, exception.reason());
        assertEquals(25, tree.snapshot("critical").active());
        for (Permit permit : permits) {
            permit.close();
        }
        assertEquals(0, tree.snapshot("critical").active());
    }

    @Test
    void lowerPriorityBorrowingIsDisabledByDefault() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 5, 0, 0, 1, Priority.NORMAL),
                List.of(
                        new BulkheadDefinition("critical", new BulkheadPolicy(5, 5, 0, 0, 10, Priority.CRITICAL), List.of()),
                        new BulkheadDefinition("background", new BulkheadPolicy(0, 5, 5, 0, 1, Priority.BACKGROUND), List.of())
                )
        ));

        BulkheadRejectedException exception = assertThrows(BulkheadRejectedException.class, () -> tree.acquireOrThrow("background"));

        assertEquals(RejectionReason.BORROW_LIMIT_REACHED, exception.reason());
        assertEquals(0, tree.snapshot("background").active());
    }

    @Test
    void configuredAnyDirectionAllowsLowerPriorityBorrowing() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 5, 0, 0, 1, Priority.NORMAL),
                List.of(
                        new BulkheadDefinition("critical", new BulkheadPolicy(5, 5, 0, 0, 10, Priority.CRITICAL), List.of()),
                        new BulkheadDefinition(
                                "background",
                                new BulkheadPolicy(0, 5, 5, 0, 1, Priority.BACKGROUND, BorrowDirection.ANY),
                                List.of()
                        )
                )
        ));

        Permit[] permits = new Permit[5];
        for (int i = 0; i < permits.length; i++) {
            permits[i] = tree.acquireOrThrow("background");
        }

        assertEquals(5, tree.snapshot("background").active());
        assertEquals(5, tree.snapshot("background").effectiveLimit());
        assertEquals(5, tree.snapshot("critical").lentCapacity());

        for (Permit permit : permits) {
            permit.close();
        }
    }

    @Test
    void totalLimitRemainsFixedWhenSiblingDemandReturnsToAFullParent() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 10, 0, 0, 1, Priority.NORMAL),
                List.of(
                        new BulkheadDefinition("critical", new BulkheadPolicy(5, 10, 5, 5, 10, Priority.CRITICAL), List.of()),
                        new BulkheadDefinition("normal", new BulkheadPolicy(5, 5, 0, 0, 5, Priority.NORMAL), List.of())
                )
        ));

        Permit[] borrowed = new Permit[10];
        for (int i = 0; i < borrowed.length; i++) {
            borrowed[i] = tree.acquireOrThrow("critical");
        }

        BulkheadRejectedException exception = assertThrows(BulkheadRejectedException.class, () -> tree.acquireOrThrow("normal"));
        assertEquals(RejectionReason.PARENT_BULKHEAD_REJECTED, exception.reason());
        assertEquals(10, tree.snapshot("application").active());
        assertEquals(10, tree.snapshot("application").effectiveLimit());
        assertEquals(10, tree.snapshot("critical").active());

        for (Permit permit : borrowed) {
            permit.close();
        }
    }

    @Test
    void detectsDoubleRelease() {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 1, 0, 0, 1, Priority.NORMAL),
                List.of()
        ));

        Permit permit = tree.acquireOrThrow("application");
        permit.close();
        assertThrows(IllegalStateException.class, permit::close);
    }

    @Test
    void concurrentAcquisitionNeverExceedsLimit() throws Exception {
        BulkheadTree tree = new BulkheadTree(new BulkheadDefinition(
                "application",
                new BulkheadPolicy(0, 4, 0, 0, 1, Priority.NORMAL),
                List.of()
        ));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger peak = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(16)) {
            Future<?>[] futures = new Future[64];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = executor.submit(() -> {
                    start.await();
                    Optional<Permit> permit = tree.tryAcquire("application");
                    permit.ifPresent(value -> {
                        try (value) {
                            peak.accumulateAndGet(tree.snapshot("application").active(), Math::max);
                        }
                    });
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }

        assertTrue(peak.get() <= 4);
        assertEquals(0, tree.snapshot("application").active());
    }
}
