package io.github.rrobetti.adaptivebulkhead;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

final class BulkheadRuntimeState {
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger effectiveLimit = new AtomicInteger();
    final AtomicInteger borrowedCapacity = new AtomicInteger();
    final AtomicInteger lentCapacity = new AtomicInteger();
    final LongAdder admitted = new LongAdder();
    final LongAdder completed = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder executorRejected = new LongAdder();
    final LongAdder cancelled = new LongAdder();
}
