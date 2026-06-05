package io.github.rrobetti.adaptivebulkhead;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveBulkheadExecutorTest {

    @Test
    void rejectedAdmissionDoesNotSubmitWork() {
        AtomicInteger submissions = new AtomicInteger();
        Executor executor = command -> submissions.incrementAndGet();
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .executor(executor)
                .build()) {
            Permit permit = bulkhead.acquireOrThrow("application");
            Future<String> future = bulkhead.submit("application", () -> "never");

            ExecutionException exception = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(BulkheadRejectedException.class, exception.getCause());
            assertEquals(0, submissions.get());
            permit.close();
        }
    }

    @Test
    void admittedTaskExecutesAndReleasesPermit() throws Exception {
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .build()) {
            Future<String> future = bulkhead.submit("application", () -> "ok");

            assertEquals("ok", future.get(5, TimeUnit.SECONDS));
            assertEquals(0, bulkhead.snapshot("application").active());
        }
    }

    @Test
    void failedTaskReleasesPermit() {
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .build()) {
            Future<String> future = bulkhead.submit("application", () -> {
                throw new IllegalStateException("boom");
            });

            ExecutionException exception = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(IllegalStateException.class, exception.getCause());
            assertEquals(0, bulkhead.snapshot("application").active());
        }
    }

    @Test
    void executorRejectionReleasesPermit() {
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .executor(command -> { throw new RejectedExecutionException("nope"); })
                .build()) {
            Future<String> future = bulkhead.submit("application", () -> "ignored");

            ExecutionException exception = assertThrows(ExecutionException.class, future::get);
            BulkheadRejectedException rejection = assertInstanceOf(BulkheadRejectedException.class, exception.getCause());
            assertEquals(RejectionReason.EXECUTOR_REJECTED, rejection.reason());
            assertEquals(0, bulkhead.snapshot("application").active());
            assertEquals(1, bulkhead.snapshot("application").executorRejected());
        }
    }

    @Test
    void fixedThreadExecutorWorks() throws Exception {
        try (var platformExecutor = Executors.newFixedThreadPool(2);
             AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                     .maxConcurrency(2)
                     .executor(platformExecutor)
                     .build()) {
            Future<Integer> future = bulkhead.submit("application", () -> 42);
            assertEquals(42, future.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void virtualThreadExecutorWorks() throws Exception {
        try (var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
             AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                     .maxConcurrency(2)
                     .executor(virtualExecutor)
                     .build()) {
            Future<Boolean> future = bulkhead.submit("application", () -> !Thread.currentThread().isVirtual() ? Boolean.FALSE : Boolean.TRUE);
            assertTrue(future.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void submitAsyncHoldsPermitUntilCompletionAndReleasesOnSuccess() {
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .build()) {
            CompletableFuture<String> delegate = new CompletableFuture<>();
            CompletionStage<String> stage = bulkhead.submitAsync("application", () -> delegate);
            assertEquals(1, bulkhead.snapshot("application").active());

            delegate.complete("done");
            assertEquals("done", stage.toCompletableFuture().join());
            assertEquals(0, bulkhead.snapshot("application").active());
        }
    }

    @Test
    void submitAsyncReleasesOnFailureCancellationSupplierThrowAndNullStage() {
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .build()) {
            CompletableFuture<String> failing = new CompletableFuture<>();
            CompletionStage<String> failedStage = bulkhead.submitAsync("application", () -> failing);
            failing.completeExceptionally(new IllegalArgumentException("boom"));
            assertThrows(CompletionException.class, () -> failedStage.toCompletableFuture().join());
            assertEquals(0, bulkhead.snapshot("application").active());

            CompletableFuture<String> cancelled = new CompletableFuture<>();
            CompletionStage<String> cancelledStage = bulkhead.submitAsync("application", () -> cancelled);
            cancelled.cancel(true);
            assertTrue(cancelledStage.toCompletableFuture().isCompletedExceptionally());
            assertEquals(0, bulkhead.snapshot("application").active());
            assertEquals(1, bulkhead.snapshot("application").cancelled());

            CompletionStage<String> thrown = bulkhead.submitAsync("application", () -> {
                throw new IllegalStateException("supplier");
            });
            assertThrows(CompletionException.class, () -> thrown.toCompletableFuture().join());
            assertEquals(0, bulkhead.snapshot("application").active());

            CompletionStage<String> nullStage = bulkhead.submitAsync("application", () -> null);
            assertThrows(CompletionException.class, () -> nullStage.toCompletableFuture().join());
            assertEquals(0, bulkhead.snapshot("application").active());
        }
    }

    @Test
    void cancellationBeforeExecutionReleasesPermit() throws Exception {
        CountDownLatch blockExecutor = new CountDownLatch(1);
        Executor queueingExecutor = command -> new Thread(() -> {
            try {
                blockExecutor.await();
                command.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }).start();

        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .executor(queueingExecutor)
                .build()) {
            Future<String> future = bulkhead.submit("application", () -> "done");
            assertTrue(future.cancel(false));
            blockExecutor.countDown();
            Thread.sleep(100);
            assertEquals(0, bulkhead.snapshot("application").active());
            assertEquals(1, bulkhead.snapshot("application").cancelled());
        }
    }

    @Test
    void listenerFailuresDoNotBreakAdmission() throws Exception {
        BulkheadEventListener listener = new BulkheadEventListener() {
            @Override
            public void onAdmitted(BulkheadEvent event) {
                throw new IllegalStateException("listener");
            }
        };
        try (AdaptiveBulkhead bulkhead = AdaptiveBulkhead.builder("application")
                .maxConcurrency(1)
                .eventListener(listener)
                .build()) {
            Future<String> future = bulkhead.submit("application", () -> "ok");
            assertEquals("ok", future.get(5, TimeUnit.SECONDS));
            assertEquals(0, bulkhead.snapshot("application").active());
        }
    }
}
