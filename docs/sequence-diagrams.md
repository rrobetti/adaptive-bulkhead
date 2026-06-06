# AdaptiveBulkhead sequence diagrams

These diagrams keep the main README short and show the most important admission flows step by step.
They stay at the request, application, lane, and permit level so the flow is easier to read than the internal helper classes.

## 1. Normal acquisition

The request fits inside the lane's own effective limit, so no borrowing is needed.

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant Application
    participant RequestedLane
    participant PathNode as each node in requested path
    participant Permit
    Caller->>Application: acquireOrThrow("critical")
    Application->>RequestedLane: check current effective limit
    RequestedLane-->>Application: own limit already covers request
    loop for each node from root to leaf
        Application->>PathNode: tryAcquire one slot
        PathNode-->>Application: granted
    end
    Application->>Permit: create permit for acquired path
    Permit-->>Application: permit
    Application-->>Caller: permit returned
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
    participant Application
    participant SiblingLane as each sibling lane
    participant RequestedLane as critical lane
    participant LendingLane as normal lane
    participant PathNode as each node in requested path
    participant Permit
    Note right of LendingLane: normal is using 2 of 6 slots,<br/>so 2 of its 4 idle slots may be lent to critical.
    Caller->>Application: acquireOrThrow("critical")
    Application->>RequestedLane: check current effective limit
    RequestedLane-->>Application: 2 extra slots needed
    loop for each sibling lane
        Application->>SiblingLane: compute demand, reserved, lendable
        SiblingLane-->>Application: child state
    end
    loop for each priority / weight round
        Application->>LendingLane: take 1 lendable slot
        LendingLane-->>Application: lend 1 slot to critical
    end
    Application-->>RequestedLane: effective limit becomes 6
    loop for each node from root to leaf
        Application->>PathNode: tryAcquire one slot under revised limit
        PathNode-->>Application: granted
    end
    Application->>Permit: create permit
    Permit-->>Application: permit
    Application-->>Caller: permit returned
    Note right of RequestedLane: Total active work still stays within the application limit of 10.
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
    participant Application
    participant RequestedLane
    Note right of Application: The application is already at its total maxConcurrency.
    Caller->>Application: acquireOrThrow("normal")
    Application->>RequestedLane: check lane limit
    RequestedLane-->>Application: lane could admit if a total slot existed
    Application->>Application: tryAcquire the next total slot
    Application-->>Caller: BulkheadRejectedException
    Note right of RequestedLane: Nothing is preempted. Existing borrowed work keeps running, and the new request fails fast.
```

## 4. Path rejection after a parent grant

In a hierarchy, admission walks from root to leaf. If a parent grants but a deeper lane rejects, the already-acquired parent slot is released immediately.

```mermaid
sequenceDiagram
    autonumber
    actor Caller
    participant Application
    participant GrantedNode as each granted node on the path
    participant RejectingNode as rejecting leaf node
    Caller->>Application: acquireOrThrow("critical/payments")
    loop for each node before the rejecting leaf
        Application->>GrantedNode: tryAcquire one slot
        GrantedNode-->>Application: granted
    end
    Application->>RejectingNode: tryAcquire one slot
    RejectingNode-->>Application: rejected
    loop rollback granted nodes from leaf to root
        Application->>GrantedNode: release previously granted slot
    end
    Application-->>Caller: BulkheadRejectedException
```
