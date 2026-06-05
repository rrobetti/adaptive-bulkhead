package io.github.rrobetti.adaptivebulkhead;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AdaptiveBulkheadBenchmark {

    @Benchmark
    public boolean rawAtomicAdmission(RawLimiterState state) {
        return state.tryAcquire();
    }

    @Benchmark
    public Optional<Permit> flatAdmission(FlatState state) {
        Optional<Permit> permit = state.bulkhead.tryAcquire("application");
        permit.ifPresent(Permit::close);
        return permit;
    }

    @Benchmark
    public Optional<Permit> hierarchyDepth3(HierarchyDepth3State state) {
        Optional<Permit> permit = state.bulkhead.tryAcquire("critical/leaf");
        permit.ifPresent(Permit::close);
        return permit;
    }

    @Benchmark
    public Optional<Permit> hierarchyDepth5(HierarchyDepth5State state) {
        Optional<Permit> permit = state.bulkhead.tryAcquire("l1/l2/l3/l4");
        permit.ifPresent(Permit::close);
        return permit;
    }

    @Benchmark
    public Optional<Permit> borrowingEnabled(BorrowingState state) {
        Optional<Permit> permit = state.bulkhead.tryAcquire("background");
        permit.ifPresent(Permit::close);
        return permit;
    }

    @Benchmark
    public java.util.concurrent.Future<Integer> platformThreadSubmission(SubmissionState state) {
        return state.platformBulkhead.submit("application", () -> 1);
    }

    @Benchmark
    public java.util.concurrent.Future<Integer> virtualThreadSubmission(SubmissionState state) {
        return state.virtualBulkhead.submit("application", () -> 1);
    }

    @State(Scope.Thread)
    public static class RawLimiterState {
        private final AtomicInteger active = new AtomicInteger();

        @Param({"8", "64", "256"})
        int limit;

        public boolean tryAcquire() {
            while (true) {
                int current = active.get();
                if (current >= limit) {
                    return false;
                }
                if (active.compareAndSet(current, current + 1)) {
                    active.decrementAndGet();
                    return true;
                }
            }
        }
    }

    @State(Scope.Benchmark)
    public static class FlatState {
        AdaptiveBulkhead bulkhead;

        @Setup(Level.Trial)
        public void setup() {
            bulkhead = AdaptiveBulkhead.builder("application").maxConcurrency(64).build();
        }
    }

    @State(Scope.Benchmark)
    public static class HierarchyDepth3State {
        AdaptiveBulkhead bulkhead;

        @Setup(Level.Trial)
        public void setup() {
            bulkhead = AdaptiveBulkhead.builder("application")
                    .maxConcurrency(64)
                    .child("critical", child -> child.maxConcurrency(32).guaranteedConcurrency(16).maximumBorrow(16).minimumRetainedCapacity(8))
                    .child("normal", child -> child.maxConcurrency(32).guaranteedConcurrency(16).maximumBorrow(16).minimumRetainedCapacity(8))
                    .build();
            bulkhead = AdaptiveBulkhead.builder("application")
                    .maxConcurrency(64)
                    .child("critical", child -> child
                            .maxConcurrency(32)
                            .guaranteedConcurrency(16)
                            .maximumBorrow(16)
                            .minimumRetainedCapacity(8)
                            .child("leaf", leaf -> leaf.maxConcurrency(32).guaranteedConcurrency(16).maximumBorrow(16).minimumRetainedCapacity(8)))
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class HierarchyDepth5State {
        AdaptiveBulkhead bulkhead;

        @Setup(Level.Trial)
        public void setup() {
            bulkhead = AdaptiveBulkhead.builder("application")
                    .maxConcurrency(64)
                    .child("l1", l1 -> l1.maxConcurrency(64).guaranteedConcurrency(32).maximumBorrow(32).minimumRetainedCapacity(16)
                            .child("l2", l2 -> l2.maxConcurrency(64).guaranteedConcurrency(32).maximumBorrow(32).minimumRetainedCapacity(16)
                                    .child("l3", l3 -> l3.maxConcurrency(64).guaranteedConcurrency(32).maximumBorrow(32).minimumRetainedCapacity(16)
                                            .child("l4", l4 -> l4.maxConcurrency(64).guaranteedConcurrency(32).maximumBorrow(32).minimumRetainedCapacity(16))))
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class BorrowingState {
        AdaptiveBulkhead bulkhead;

        @Setup(Level.Trial)
        public void setup() {
            bulkhead = AdaptiveBulkhead.builder("application")
                    .maxConcurrency(100)
                    .child("critical", child -> child.guaranteedConcurrency(40).maxConcurrency(80).maximumBorrow(40).minimumRetainedCapacity(40).priority(Priority.CRITICAL).weight(10))
                    .child("normal", child -> child.guaranteedConcurrency(30).maxConcurrency(60).maximumBorrow(30).minimumRetainedCapacity(15).priority(Priority.NORMAL).weight(5))
                    .child("background", child -> child.guaranteedConcurrency(0).maxConcurrency(30).maximumBorrow(30).minimumRetainedCapacity(0).priority(Priority.BACKGROUND).weight(1))
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class SubmissionState {
        AdaptiveBulkhead platformBulkhead;
        AdaptiveBulkhead virtualBulkhead;
        ExecutorService platformExecutor;
        ExecutorService virtualExecutor;

        @Setup(Level.Trial)
        public void setup() {
            platformExecutor = Executors.newFixedThreadPool(8);
            virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
            platformBulkhead = AdaptiveBulkhead.builder("application").maxConcurrency(64).executor(platformExecutor).build();
            virtualBulkhead = AdaptiveBulkhead.builder("application").maxConcurrency(64).executor(virtualExecutor).build();
        }
    }
}
