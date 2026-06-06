# AdaptiveBulkhead sequence diagrams

These diagrams keep the main README short and show the most important admission flows step by step.

## 1. Normal acquisition

The request fits inside the lane's own effective limit, so no borrowing is needed.

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant Bulkhead
    participant Tree
    participant Allocator
    participant Application
    participant TargetLane
    participant Permit
    Caller->>Bulkhead: acquireOrThrow("critical")
    Bulkhead->>Tree: tryAcquire(path)
    Tree->>Allocator: refresh effective limits
    Allocator-->>Tree: lane effective limit already covers request
    Tree->>Application: tryAcquire one slot
    Application-->>Tree: granted
    Tree->>TargetLane: tryAcquire one slot
    TargetLane-->>Tree: granted
    Tree->>Permit: create permit for acquired path
    Permit-->>Bulkhead: permit
    Bulkhead-->>Caller: permit returned
    Caller->>Permit: close()
    Permit->>TargetLane: release
    Permit->>Application: release
```

## 2. Higher-priority lane borrows from a lower-priority lane

Example setup:

- application total limit = 10
- `critical` guaranteed = 4, max = 6, maximum borrow = 2
- `normal` guaranteed = 6, minimum retained = 2

If `normal` is only using 2 slots, 2 of its idle slots may be lent to `critical`.

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant Bulkhead
    participant Tree
    participant Allocator
    participant Application
    participant CriticalLane
    participant NormalLane
    participant Permit
    Note right of NormalLane: normal is using 2 of 6 slots,<br/>so 2 of its 4 idle slots may be lent to critical.
    Caller->>Bulkhead: acquireOrThrow("critical")
    Bulkhead->>Tree: tryAcquire(path)
    Tree->>Allocator: recompute effective limits
    Allocator->>NormalLane: reserve minimum retained capacity
    NormalLane-->>Allocator: 2 lendable slots remain
    Allocator-->>Tree: critical effective limit becomes 6
    Tree->>Application: tryAcquire one slot
    Application-->>Tree: granted
    Tree->>CriticalLane: tryAcquire one slot under revised limit
    CriticalLane-->>Tree: granted
    Tree->>Permit: create permit
    Permit-->>Bulkhead: permit
    Bulkhead-->>Caller: permit returned
    Note right of Application: Total active work still stays within the application limit of 10.
    Caller->>Permit: close()
    Permit->>CriticalLane: release
    Permit->>Application: release
```

## 3. Fail-fast rejection with rollback

This shows why the total limit stays fixed even when a lane previously borrowed capacity: if no total slot is free now, the new request is rejected immediately.

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant Bulkhead
    participant Tree
    participant Allocator
    participant Application
    participant RequestedLane
    Note right of Application: The application is already at its total maxConcurrency.
    Caller->>Bulkhead: acquireOrThrow("normal")
    Bulkhead->>Tree: tryAcquire(path)
    Tree->>Allocator: recompute effective limits
    Allocator-->>Tree: latest limits prepared
    Tree->>Application: tryAcquire one slot
    Application-->>Tree: rejected
    Tree-->>Bulkhead: rejection (no permit created)
    Bulkhead-->>Caller: BulkheadRejectedException
    Note right of RequestedLane: Nothing is preempted. Existing borrowed work keeps running, and the new request fails fast.
```

## 4. Path rejection after a parent grant

In a hierarchy, admission walks from root to leaf. If a parent grants but a deeper lane rejects, the already-acquired parent slot is released immediately.

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant Bulkhead
    participant Tree
    participant Application
    participant CriticalGroup
    participant PaymentsLane
    Caller->>Bulkhead: acquireOrThrow("critical/payments")
    Bulkhead->>Tree: tryAcquire(path)
    Tree->>Application: tryAcquire one slot
    Application-->>Tree: granted
    Tree->>CriticalGroup: tryAcquire one slot
    CriticalGroup-->>Tree: granted
    Tree->>PaymentsLane: tryAcquire one slot
    PaymentsLane-->>Tree: rejected
    Tree->>CriticalGroup: release previously granted slot
    Tree->>Application: release previously granted slot
    Tree-->>Bulkhead: rejection with parent rollback
    Bulkhead-->>Caller: BulkheadRejectedException
```
