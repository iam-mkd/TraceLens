package io.tracelens.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Per-cohort observations; each request retains its cohort even after admission closes. */
public final class LabObservation {
    public final String id;
    public final String mode;
    public final int delayMillis;
    public final int expectedRequests;
    public final LongAdder admitted = new LongAdder();
    public final LongAdder completed = new LongAdder();
    public final LongAdder failures = new LongAdder();
    public final LongAdder initialHits = new LongAdder();
    public final LongAdder initialMisses = new LongAdder();
    public final LongAdder initialLookupErrors = new LongAdder();
    public final LongAdder secondaryReads = new LongAdder();
    public final LongAdder secondaryHits = new LongAdder();
    public final LongAdder secondaryLookupErrors = new LongAdder();
    public final LongAdder waiters = new LongAdder();
    public final LongAdder writeFailures = new LongAdder();
    public final LongAdder sqlAttempts = new LongAdder();
    public final LongAdder sqlSuccesses = new LongAdder();
    public final LongAdder sqlFailures = new LongAdder();
    public final AtomicInteger activeSql = new AtomicInteger();
    public final AtomicInteger peakSql = new AtomicInteger();
    public final AtomicLong firstMissNanos = new AtomicLong();
    public final AtomicLong refillNanos = new AtomicLong();
    public final ConcurrentLinkedQueue<Long> acquisitionNanos = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<Long> queryNanos = new ConcurrentLinkedQueue<>();
    public final long preparedNanos = System.nanoTime();
    public volatile long openedNanos;
    public volatile long closedNanos;
    public volatile long drainedNanos;

    public LabObservation(String id, String mode, int expectedRequests, int delayMillis) {
        this.id = id;
        this.mode = mode;
        this.expectedRequests = expectedRequests;
        this.delayMillis = delayMillis;
    }

    public void sqlStart() {
        sqlAttempts.increment();
        int now = activeSql.incrementAndGet();
        peakSql.accumulateAndGet(now, Math::max);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cohortId", id);
        result.put("mode", mode);
        result.put("delayMillis", delayMillis);
        result.put("expectedRequests", expectedRequests);
        result.put("admitted", admitted.sum());
        result.put("completed", completed.sum());
        result.put("failures", failures.sum());
        result.put("initialHits", initialHits.sum());
        result.put("initialMisses", initialMisses.sum());
        result.put("initialLookupErrors", initialLookupErrors.sum());
        result.put("secondaryReads", secondaryReads.sum());
        result.put("secondaryHits", secondaryHits.sum());
        result.put("secondaryLookupErrors", secondaryLookupErrors.sum());
        result.put("coalescedWaiters", waiters.sum());
        result.put("cacheWriteFailures", writeFailures.sum());
        result.put("sqlAttempts", sqlAttempts.sum());
        result.put("sqlSuccesses", sqlSuccesses.sum());
        result.put("sqlFailures", sqlFailures.sum());
        result.put("activeSql", activeSql.get());
        result.put("peakSql", peakSql.get());
        result.put("connectionAcquisitionNanos", new ArrayList<>(acquisitionNanos));
        result.put("sqlDurationNanos", new ArrayList<>(queryNanos));
        result.put("eventsNanosSincePreparation", Map.of(
                "opened", relative(openedNanos), "firstMiss", relative(firstMissNanos.get()),
                "refill", relative(refillNanos.get()), "closed", relative(closedNanos),
                "drained", relative(drainedNanos)));
        return result;
    }

    private long relative(long value) {
        return value == 0 ? -1 : value - preparedNanos;
    }
}
