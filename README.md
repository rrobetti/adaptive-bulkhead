# AdaptiveBulkhead

> AdaptiveBulkhead is a hierarchical, priority-aware bulkhead and admission-control library for Java. It isolates workloads, shares unused capacity safely, and immediately rejects work when protected capacity is unavailable.

AdaptiveBulkhead decides whether work may start **immediately**. It is not a thread pool, it does not queue, and it never blocks callers waiting for capacity. When protected capacity is unavailable, admission fails fast with a detailed rejection reason.

## Modules

- `adaptive-bulkhead-core` — policies, hierarchy, atomic admission, permits, borrowing, metrics, and listener APIs
- `adaptive-bulkhead-executor` — `AdaptiveBulkhead`, executor integration, synchronous execution, and `CompletionStage` support
- `adaptive-bulkhead-examples` — runnable examples

## Requirements and design trade-offs

- Java 21+
- fail-fast admission only
- atomic-counter based limits with short compare-and-set loops
- no queues, semaphores, waits, timeouts, task ageing, or preemption
- priorities affect **capacity allocation**, not JVM thread priority or task scheduling order
- borrowing is deterministic and conservative: reserve guarantees, compute lendable capacity, then allocate borrowed slots by priority and weight

## Why it is not a thread pool

AdaptiveBulkhead controls *admission*. Executors control *execution*. Admission is performed **before** work is submitted, so rejected work never creates a virtual thread and never enters an executor queue.

## Quick start

```java
AdaptiveBulkhead bulkhead =
        AdaptiveBulkhead.builder("application")
                .maxConcurrency(100)
                .child("payments", lane -> lane
                        .guaranteedConcurrency(40)
                        .maxConcurrency(80)
                        .maximumBorrow(40)
                        .minimumRetainedCapacity(40)
                        .priority(Priority.CRITICAL)
                        .weight(10))
                .child("api", lane -> lane
                        .guaranteedConcurrency(30)
                        .maxConcurrency(60)
                        .maximumBorrow(30)
                        .minimumRetainedCapacity(15)
                        .priority(Priority.NORMAL)
                        .weight(5))
                .child("reports", lane -> lane
                        .guaranteedConcurrency(0)
                        .maxConcurrency(30)
                        .maximumBorrow(30)
                        .minimumRetainedCapacity(0)
                        .priority(Priority.BACKGROUND)
                        .weight(1))
                .build();
```

### Direct permit acquisition

```java
try (Permit permit = bulkhead.acquireOrThrow("payments")) {
    processPayment();
}
```

```java
Optional<Permit> permit = bulkhead.tryAcquire("payments");
if (permit.isEmpty()) {
    throw new BulkheadRejectedException("payments", "payments", RejectionReason.CONCURRENCY_LIMIT_REACHED);
}
try (Permit ignored = permit.get()) {
    processPayment();
}
```

### Virtual-thread executor example

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    AdaptiveBulkhead virtualBulkhead = bulkhead.withExecutor(executor);
    Future<Result> result = virtualBulkhead.submit("payments", this::processPayment);
}
```

### Platform-thread executor example

```java
try (var executor = Executors.newFixedThreadPool(20)) {
    AdaptiveBulkhead platformBulkhead = bulkhead.withExecutor(executor);
    Future<Result> result = platformBulkhead.submit("payments", this::processPayment);
}
```

### Hierarchy example

```java
AdaptiveBulkhead hierarchy =
        AdaptiveBulkhead.builder("application")
                .maxConcurrency(100)
                .child("critical", critical -> critical
                        .guaranteedConcurrency(40)
                        .maxConcurrency(80)
                        .child("payments", payments -> payments
                                .guaranteedConcurrency(20)
                                .maxConcurrency(40)
                                .maximumBorrow(20)
                                .minimumRetainedCapacity(10)))
                .build();

try (Permit permit = hierarchy.acquireOrThrow("critical/payments")) {
    processPayment();
}
```

A `critical/payments` acquisition consumes capacity from `application -> critical -> payments` and rolls back safely if any node rejects.

### Priority and borrowing example

Unused guaranteed capacity can be borrowed temporarily:

```java
AdaptiveBulkhead borrowing =
        AdaptiveBulkhead.builder("application")
                .maxConcurrency(70)
                .child("critical", lane -> lane
                        .guaranteedConcurrency(40)
                        .maxConcurrency(40)
                        .minimumRetainedCapacity(40)
                        .priority(Priority.CRITICAL)
                        .weight(10))
                .child("normal", lane -> lane
                        .guaranteedConcurrency(30)
                        .maxConcurrency(30)
                        .minimumRetainedCapacity(15)
                        .priority(Priority.NORMAL)
                        .weight(5))
                .child("background", lane -> lane
                        .guaranteedConcurrency(0)
                        .maxConcurrency(20)
                        .maximumBorrow(20)
                        .minimumRetainedCapacity(0)
                        .priority(Priority.BACKGROUND)
                        .weight(1))
                .build();
```

Borrowed work keeps running once it has been admitted. If higher-priority traffic comes back, a lower-priority lane might be over its new limit for a short time. While that happens, the lane will not admit any new work until enough running work finishes and capacity is available again.

### `CompletionStage` example

```java
CompletionStage<Response> response =
        bulkhead.submitAsync(
                "api",
                () -> httpClient.sendAsync(request, handler)
        );
```

The permit remains held until the stage completes successfully, fails, or is cancelled.

### Metrics example

```java
BulkheadSnapshot snapshot = bulkhead.snapshot("payments");
System.out.println(snapshot.active());
System.out.println(snapshot.effectiveLimit());
System.out.println(snapshot.borrowedCapacity());
```

`BulkheadSnapshot` exposes exact active counts plus cumulative admitted/completed/rejected/cancelled counters.

### Listener hooks

Implement `BulkheadEventListener` to observe admissions, rejections, starts, completions, and capacity changes. Listener failures are swallowed so they never break admission.

## Rejection reasons

- `CONCURRENCY_LIMIT_REACHED`
- `PARENT_BULKHEAD_REJECTED`
- `BORROW_LIMIT_REACHED`
- `BULKHEAD_DISABLED`
- `EXECUTOR_REJECTED`
- `CANCELLED_BEFORE_EXECUTION`

`BulkheadRejectedException` reports the requested bulkhead, the rejecting bulkhead, and the rejection reason.

## Metrics and observability

- `BulkheadSnapshot` — immutable point-in-time metrics
- `BulkheadEventListener` — no-op by default
- `BulkheadEvent` — requested bulkhead, emitting node, rejection reason, timestamp, and snapshot

## Testing and verification

- unit and concurrency tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH mvn test`
- long-running stress profile: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH mvn -Pstress test`
- JMH benchmarks jar: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH mvn -Pbenchmarks -pl adaptive-bulkhead-executor -am package`

## Known limitations

- no queueing or blocking acquisition
- no timed acquisition or waiting admission
- no dynamic runtime reconfiguration
- no adaptive latency/RPS controllers
- no reactive-streams integration beyond `CompletionStage`
- no distributed coordination or Spring integration yet
- borrowing uses a conservative deterministic allocator rather than a background controller

## Architecture overview

- `AdaptiveBulkhead` exposes the public API and executor integration.
- `BulkheadTree` manages hierarchy admission and rollback.
- `HierarchyPermit` releases node permits in reverse order and throws on double release.
- `BorrowingAllocator` recalculates effective limits on admission attempts and releases.
- `BulkheadSnapshot` and `BulkheadEventListener` provide observability.

## Benchmark coverage

The included JMH benchmark compares:

- raw atomic fail-fast admission
- flat bulkhead admission
- hierarchy depth 3 admission
- hierarchy depth 5 admission
- borrowing-enabled admission
- platform-thread submission
- virtual-thread submission

## Future extension points

- runtime configuration reloads
- richer metrics integrations
- alternative borrowing allocators
- optional queueing/timeouts in a future major design
