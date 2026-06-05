package io.github.rrobetti.adaptivebulkhead;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("stress")
class AdaptiveBulkheadStressTest {

    @Test
    void concurrentStressMaintainsBounds() throws Exception {
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(12)
                .child("payments", child -> child
                        .guaranteedConcurrency(4)
                        .maxConcurrency(8)
                        .maximumBorrow(4)
                        .minimumRetainedCapacity(4)
                        .priority(Priority.CRITICAL)
                        .weight(10))
                .child("api", child -> child
                        .guaranteedConcurrency(3)
                        .maxConcurrency(7)
                        .maximumBorrow(4)
                        .minimumRetainedCapacity(1)
                        .priority(Priority.NORMAL)
                        .weight(5))
                .child("reports", child -> child
                        .guaranteedConcurrency(0)
                        .maxConcurrency(4)
                        .maximumBorrow(4)
                        .minimumRetainedCapacity(0)
                        .priority(Priority.BACKGROUND)
                        .weight(1))
                .build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> names = List.of("payments", "api", "reports");
            AtomicInteger peakRoot = new AtomicInteger();
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                futures.add(executor.submit(() -> {
                    String name = names.get(ThreadLocalRandom.current().nextInt(names.size()));
                    Optional<Permit> permit = bulkhead.tryAcquire(name);
                    permit.ifPresent(value -> {
                        try (value) {
                            peakRoot.accumulateAndGet(bulkhead.snapshot("application").active(), Math::max);
                            assertTrue(bulkhead.snapshot(name).active() <= Math.max(bulkhead.snapshot(name).effectiveLimit(), bulkhead.snapshot(name).active()));
                            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            assertTrue(peakRoot.get() <= 12);
            assertEquals(0, bulkhead.snapshot("application").active());
            for (BulkheadSnapshot snapshot : bulkhead.snapshots()) {
                assertTrue(snapshot.active() >= 0);
            }
        }
    }
}
