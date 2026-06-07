# AdaptiveBulkhead

[![CI](https://github.com/rrobetti/adaptive-bulkhead/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/rrobetti/adaptive-bulkhead/actions/workflows/ci.yml)

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
- borrowing is deterministic and conservative: reserve guarantees, compute lendable capacity, then allocate borrowed slots by priority and weight; by default, only higher-priority lanes may borrow from lower-priority lanes

## Why it is not a thread pool

AdaptiveBulkhead controls *admission*. Executors control *execution*. Admission is performed **before** work is submitted, so rejected work never creates a virtual thread and never enters an executor queue.

## Borrowing is not work stealing

AdaptiveBulkhead does **not** implement classic work stealing.

In a work-stealing design, idle workers usually pull queued work from other workers. AdaptiveBulkhead does not move queued work, move running work, or reassign a request from one lane to another. It only recalculates how many **new admissions** each lane may take right now.

So the more accurate term here is **slot borrowing** or **capacity borrowing**:

- a higher-priority lane may use idle capacity from a lower-priority lane
- already admitted work keeps running where it started
- if no total slot is free, the new request still fails fast

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

Borrowing never changes the parent or application total limit. It only changes each lane's current effective limit. A lane can temporarily sit above its latest effective limit because already admitted borrowed work is allowed to finish, but the total active count still never exceeds the parent `maxConcurrency`.

```java
AdaptiveBulkhead borrowing =
        AdaptiveBulkhead.builder("application")
                .maxConcurrency(10)
                .child("critical", lane -> lane
                        .guaranteedConcurrency(4)
                        .maxConcurrency(6)
                        .maximumBorrow(2)
                        .minimumRetainedCapacity(4)
                        .priority(Priority.CRITICAL)
                        .weight(10))
                .child("normal", lane -> lane
                        .guaranteedConcurrency(6)
                        .maxConcurrency(6)
                        .minimumRetainedCapacity(2)
                        .priority(Priority.NORMAL)
                        .weight(5))
                .build();
```

Default direction:

- `critical` may borrow from `normal` because `critical` has higher priority.
- `normal` may not borrow from `critical` unless you opt in to a different direction.

Simple examples:

- **Example 1: higher priority borrows from a lower lane**
  - The application has 10 total slots.
  - `critical` guarantees 4 slots and may borrow 2 more.
  - `normal` guarantees 6 slots and keeps at least 2 for itself.
  - If `normal` is using only 2 slots, it has 4 idle guaranteed slots, and 2 of them may be lent.
  - `critical` can run 6 requests total: its own 4 plus 2 borrowed from `normal`.

- **Example 2: total limit stays fixed**
  - `critical` already has 6 active requests.
  - The whole application is full at 10 active requests.
  - A new `normal` request arrives.
  - The application still does not go above 10 total active requests.
  - That new `normal` request fails fast if no total slot is free.
  - At the same time, `critical` can temporarily look over its latest revised lane limit until one of its already admitted borrowed requests finishes.

- **Example 3: opting in to lower-from-higher borrowing**
  - If you explicitly configure a lower lane with `.borrowDirection(BorrowDirection.ANY)`, it may borrow from an idle higher lane.
  - If a higher-priority request later arrives while all total slots are already busy, that higher-priority request still fails fast right then.
  - Borrowed work is not interrupted. The lower lane simply stops receiving new admissions until capacity is restored.

Optional opt-in:

```java
AdaptiveBulkhead flexibleBorrowing =
        AdaptiveBulkhead.builder("application")
                .maxConcurrency(6)
                .child("critical", lane -> lane
                        .guaranteedConcurrency(2)
                        .maxConcurrency(2)
                        .minimumRetainedCapacity(0)
                        .priority(Priority.CRITICAL))
                .child("background", lane -> lane
                        .guaranteedConcurrency(0)
                        .maxConcurrency(4)
                        .maximumBorrow(2)
                        .borrowDirection(BorrowDirection.ANY)
                        .minimumRetainedCapacity(0)
                        .priority(Priority.BACKGROUND))
                .build();
```

### Flow diagrams

See [docs/sequence-diagrams.md](docs/sequence-diagrams.md) for Mermaid sequence diagrams covering:

- a normal acquisition that stays inside the lane's own limit
- an acquisition that succeeds by borrowing from a lower-priority sibling
- a fail-fast rejection with rollback when a path cannot be admitted

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
