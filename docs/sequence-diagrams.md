# AdaptiveBulkhead sequence diagrams

These diagrams keep the main README short and show the most important admission flows step by step.
They also mirror the implementation loops: `BulkheadTree` walks the path from root to leaf, and `BorrowingAllocator` iterates sibling lanes when it recalculates effective limits.

## 1. Normal acquisition

The request fits inside the lane's own effective limit, so no borrowing is needed.

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant Bulkhead
    participant Tree
    participant Allocator
    participant PathNode as each node in requested path
    participant Permit
    Caller->>Bulkhead: acquireOrThrow("critical")
    Bulkhead->>Tree: tryAcquire(path)
    Tree->>Allocator: refresh effective limits
    Allocator-->>Tree: lane effective limit already covers request
    loop for each node from root to leaf
        Tree->>PathNode: tryAcquire one slot
        PathNode-->>Tree: granted
    end
    Tree->>Permit: create permit for acquired path
    Permit-->>Bulkhead: permit
    Bulkhead-->>Caller: permit returned
    Caller->>Permit: close()
    loop for each node from leaf to root
        Permit->>PathNode: release
    end
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
    participant SiblingLane as each sibling lane
    participant CriticalLane
    participant NormalLane
    participant PathNode as each node in requested path
    participant Permit
    Note right of NormalLane: normal is using 2 of 6 slots,<br/>so 2 of its 4 idle slots may be lent to critical.
    Caller->>Bulkhead: acquireOrThrow("critical")
    Bulkhead->>Tree: tryAcquire(path)
    Tree->>Allocator: recompute effective limits
    loop for each sibling lane
        Allocator->>SiblingLane: compute demand, reserved, lendable
        SiblingLane-->>Allocator: child state
    end
    loop for each priority / weight round
        Allocator->>NormalLane: take 1 lendable slot
        NormalLane-->>Allocator: lend 1 slot to critical
    end
    Allocator-->>Tree: critical effective limit becomes 6
    loop for each node from root to leaf
        Tree->>PathNode: tryAcquire one slot under revised limit
        PathNode-->>Tree: granted
    end
    Tree->>Permit: create permit
    Permit-->>Bulkhead: permit
    Bulkhead-->>Caller: permit returned
    Note right of CriticalLane: Total active work still stays within the application limit of 10.
    Caller->>Permit: close()
    loop for each node from leaf to root
        Permit->>PathNode: release
    end
```

## 3. Fail-fast rejection at the application limit

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
    Tree->>Application: tryAcquire the first slot in the path
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
    participant GrantedNode as each granted node on the path
    participant RejectingNode as rejecting leaf node
    Caller->>Bulkhead: acquireOrThrow("critical/payments")
    Bulkhead->>Tree: tryAcquire(path)
    loop for each node before the rejecting leaf
        Tree->>GrantedNode: tryAcquire one slot
        GrantedNode-->>Tree: granted
    end
    Tree->>RejectingNode: tryAcquire one slot
    RejectingNode-->>Tree: rejected
    loop rollback granted nodes from leaf to root
        Tree->>GrantedNode: release previously granted slot
    end
    Tree-->>Bulkhead: rejection with parent rollback
    Bulkhead-->>Caller: BulkheadRejectedException
```
