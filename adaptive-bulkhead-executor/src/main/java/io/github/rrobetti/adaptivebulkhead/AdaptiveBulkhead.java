package io.github.rrobetti.adaptivebulkhead;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AdaptiveBulkhead implements AutoCloseable {
    private final BulkheadTree tree;
    private final Executor executor;
    private final boolean ownsExecutor;

    private AdaptiveBulkhead(BulkheadTree tree, Executor executor, boolean ownsExecutor) {
        this.tree = tree;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    public static Builder builder(String rootName) {
        return new Builder(rootName);
    }

    public AdaptiveBulkhead withExecutor(Executor executor) {
        return new AdaptiveBulkhead(tree, Objects.requireNonNull(executor, "executor"), false);
    }

    public Optional<Permit> tryAcquire(String bulkheadName) {
        return tree.tryAcquire(bulkheadName);
    }

    public Permit acquireOrThrow(String bulkheadName) {
        return tree.acquireOrThrow(bulkheadName);
    }

    public void execute(String bulkheadName, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        HierarchyPermit permit = tree.acquireOrThrow(bulkheadName);
        try {
            executor.execute(() -> {
                tree.emitStarted(permit.bulkheadName());
                try {
                    runnable.run();
                } finally {
                    permit.release(ReleaseOutcome.COMPLETED, false);
                }
            });
        } catch (RejectedExecutionException exception) {
            permit.release(ReleaseOutcome.EXECUTOR_REJECTED, false);
            throw new BulkheadRejectedException(bulkheadName, bulkheadName, RejectionReason.EXECUTOR_REJECTED);
        }
    }

    public <T> Future<T> submit(String bulkheadName, Callable<T> callable) {
        Objects.requireNonNull(callable, "callable");
        HierarchyPermit permit = tree.acquireOrThrow(bulkheadName);
        BulkheadTaskFuture<T> future = new BulkheadTaskFuture<>(permit, tree);
        try {
            executor.execute(() -> {
                if (!future.markStarted()) {
                    return;
                }
                tree.emitStarted(permit.bulkheadName());
                try {
                    future.complete(callable.call());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                } finally {
                    future.finish();
                }
            });
            return future;
        } catch (RejectedExecutionException exception) {
            future.failExecutorRejected(bulkheadName);
            return future;
        }
    }

    public <T> CompletionStage<T> submitAsync(
            String bulkheadName,
            Supplier<? extends CompletionStage<T>> supplier
    ) {
        Objects.requireNonNull(supplier, "supplier");
        HierarchyPermit permit = tree.acquireOrThrow(bulkheadName);
        tree.emitStarted(permit.bulkheadName());
        final CompletionStage<T> stage;
        try {
            stage = supplier.get();
        } catch (Throwable throwable) {
            permit.release(ReleaseOutcome.COMPLETED, false);
            return CompletableFuture.failedStage(throwable);
        }
        if (stage == null) {
            permit.release(ReleaseOutcome.COMPLETED, false);
            return CompletableFuture.failedStage(new NullPointerException("submitAsync supplier returned null"));
        }
        return stage.whenComplete((result, throwable) -> {
            if (throwable instanceof java.util.concurrent.CancellationException) {
                permit.release(ReleaseOutcome.CANCELLED, false);
            } else {
                permit.release(ReleaseOutcome.COMPLETED, false);
            }
        });
    }

    public BulkheadSnapshot snapshot(String bulkheadName) {
        return tree.snapshot(bulkheadName);
    }

    public Collection<BulkheadSnapshot> snapshots() {
        return tree.snapshots();
    }

    @Override
    public void close() {
        if (ownsExecutor && executor instanceof ExecutorService executorService) {
            executorService.close();
        }
    }

    public static final class Builder {
        private final NodeBuilder root;
        private BulkheadEventListener listener = BulkheadEventListener.noop();
        private Executor executor;

        private Builder(String rootName) {
            this.root = new NodeBuilder(rootName, null);
            this.root.priority(Priority.NORMAL);
            this.root.weight(1);
        }

        public Builder maxConcurrency(int value) {
            root.maxConcurrency(value);
            return this;
        }

        public Builder guaranteedConcurrency(int value) {
            root.guaranteedConcurrency(value);
            return this;
        }

        public Builder maximumBorrow(int value) {
            root.maximumBorrow(value);
            return this;
        }

        public Builder minimumRetainedCapacity(int value) {
            root.minimumRetainedCapacity(value);
            return this;
        }

        public Builder priority(Priority priority) {
            root.priority(priority);
            return this;
        }

        public Builder weight(int weight) {
            root.weight(weight);
            return this;
        }

        public Builder child(String name, Consumer<NodeBuilder> builder) {
            root.child(name, builder);
            return this;
        }

        public Builder eventListener(BulkheadEventListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public AdaptiveBulkhead build() {
            Executor resolvedExecutor = executor;
            boolean ownsExecutor = false;
            if (resolvedExecutor == null) {
                resolvedExecutor = Executors.newVirtualThreadPerTaskExecutor();
                ownsExecutor = true;
            }
            return new AdaptiveBulkhead(new BulkheadTree(root.toDefinition(true), listener), resolvedExecutor, ownsExecutor);
        }
    }

    public static final class NodeBuilder {
        private final String name;
        private final NodeBuilder parent;
        private final List<NodeBuilder> children = new ArrayList<>();
        private Integer guaranteedConcurrency = 0;
        private Integer maxConcurrency;
        private Integer maximumBorrow = 0;
        private Integer minimumRetainedCapacity = 0;
        private Integer weight = 1;
        private Priority priority = Priority.NORMAL;

        private NodeBuilder(String name, NodeBuilder parent) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            this.name = name;
            this.parent = parent;
        }

        public NodeBuilder guaranteedConcurrency(int value) {
            this.guaranteedConcurrency = value;
            return this;
        }

        public NodeBuilder maxConcurrency(int value) {
            this.maxConcurrency = value;
            return this;
        }

        public NodeBuilder maximumBorrow(int value) {
            this.maximumBorrow = value;
            return this;
        }

        public NodeBuilder minimumRetainedCapacity(int value) {
            this.minimumRetainedCapacity = value;
            return this;
        }

        public NodeBuilder priority(Priority priority) {
            this.priority = Objects.requireNonNull(priority, "priority");
            return this;
        }

        public NodeBuilder weight(int value) {
            this.weight = value;
            return this;
        }

        public NodeBuilder child(String childName, Consumer<NodeBuilder> builder) {
            NodeBuilder child = new NodeBuilder(childName, this);
            builder.accept(child);
            children.add(child);
            return this;
        }

        private BulkheadDefinition toDefinition(boolean rootNode) {
            if (maxConcurrency == null) {
                throw new IllegalStateException("maxConcurrency must be configured for bulkhead '" + name + "'");
            }
            BulkheadPolicy policy = new BulkheadPolicy(
                    guaranteedConcurrency,
                    maxConcurrency,
                    maximumBorrow,
                    minimumRetainedCapacity,
                    weight,
                    priority
            );
            if (rootNode && policy.maxConcurrency() <= 0) {
                throw new IllegalArgumentException("root maximum concurrency must be positive");
            }
            List<BulkheadDefinition> childDefinitions = children.stream()
                    .map(child -> child.toDefinition(false))
                    .toList();
            return new BulkheadDefinition(name, policy, childDefinitions);
        }
    }

    private static final class BulkheadTaskFuture<T> extends CompletableFuture<T> {
        private final HierarchyPermit permit;
        private final BulkheadTree tree;
        private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);

        private BulkheadTaskFuture(HierarchyPermit permit, BulkheadTree tree) {
            this.permit = permit;
            this.tree = tree;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!state.compareAndSet(TaskState.QUEUED, TaskState.CANCELLED)) {
                return false;
            }
            super.cancel(mayInterruptIfRunning);
            permit.release(ReleaseOutcome.CANCELLED, false);
            return true;
        }

        boolean markStarted() {
            return state.compareAndSet(TaskState.QUEUED, TaskState.STARTED);
        }

        void finish() {
            if (state.compareAndSet(TaskState.STARTED, TaskState.FINISHED)) {
                permit.release(ReleaseOutcome.COMPLETED, false);
            }
        }

        void failExecutorRejected(String bulkheadName) {
            if (state.compareAndSet(TaskState.QUEUED, TaskState.FINISHED)) {
                permit.release(ReleaseOutcome.EXECUTOR_REJECTED, false);
                completeExceptionally(new BulkheadRejectedException(
                        bulkheadName,
                        bulkheadName,
                        RejectionReason.EXECUTOR_REJECTED
                ));
            }
        }
    }

    private enum TaskState {
        QUEUED,
        STARTED,
        CANCELLED,
        FINISHED
    }
}
