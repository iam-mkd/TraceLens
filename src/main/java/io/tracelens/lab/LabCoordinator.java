package io.tracelens.lab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** All admission and lifecycle transitions are serialized on this object. */
@Component
public class LabCoordinator {
    private static final long LEASE_NANOS = Duration.ofSeconds(30).toNanos();
    private final boolean enabled;
    private final byte[] token;
    private final LongSupplier clock;
    private int productRequests;
    private String session;
    private long deadline;
    private LabObservation cohort;
    private boolean open;
    private boolean expired;
    private boolean aborting;
    private final Set<String> requestIds = new HashSet<>();
    private final Set<String> cohortIds = new HashSet<>();
    private String preparingCohortId;

    @Autowired
    public LabCoordinator(Environment env) { this(env, System::nanoTime); }

    LabCoordinator(Environment env, LongSupplier clock) {
        this.clock = clock;
        enabled = env.matchesProfiles("lab");
        String configured = env.getProperty("TRACELENS_LAB_TOKEN", "");
        token = configured.getBytes(StandardCharsets.UTF_8);
        if (enabled) {
            String db = env.getProperty("spring.datasource.url", "");
            String redis = env.getProperty("spring.data.redis.host", "");
            int port = env.getProperty("spring.data.redis.port", Integer.class, 6379);
            int pool = env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 16);
            int threads = env.getProperty("server.tomcat.threads.max", Integer.class, 64);
            String ttl = env.getProperty("tracelens.cache.ttl", "300s");
            if (!configured.matches("[A-Za-z0-9_-]{32,128}")
                    || !db.matches("^jdbc:postgresql://postgres:5432/tracelens(?:\\?.*)?$")
                    || !redis.equals("redis") || port != 6379 || pool != 16 || threads != 64 || !ttl.equals("300s")) {
                throw new IllegalStateException("Lab requires an isolated Compose target, 16 database connections, 64 server threads, a 300s TTL, and a 32-128 character token");
            }
        }
    }

    public boolean authenticate(String candidate) {
        return enabled && candidate != null && candidate.length() <= 128
                && MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
    }

    public boolean enabled() { return enabled; }

    public synchronized String start() {
        expireIfNeeded();
        if (session != null || productRequests != 0) throw new IllegalStateException("Product traffic or another session is active");
        session = UUID.randomUUID().toString();
        expired = false;
        aborting = false;
        cohortIds.clear();
        touch();
        return session;
    }

    public synchronized void begin(String sessionId, String cohortId) {
        checkSession(sessionId);
        if (aborting || preparingCohortId != null || productRequests != 0 || open) throw new IllegalStateException("Previous cohort has not drained or is preparing");
        if (cohort != null && cohort.drainedNanos == 0) throw new IllegalStateException("Previous cohort has not been collected");
        if (cohortId == null || cohortIds.size() >= 60 || !cohortIds.add(cohortId)) {
            throw new IllegalStateException("Cohort identifier was already used or session is full");
        }
        preparingCohortId = cohortId;
        cohort = null;
        requestIds.clear();
    }

    public synchronized void open(String sessionId, LabObservation value) {
        checkSession(sessionId);
        if (aborting || cohort != null || productRequests != 0 || !value.id.equals(preparingCohortId)) throw new IllegalStateException("Cannot open cohort");
        preparingCohortId = null;
        cohort = value;
        cohort.openedNanos = System.nanoTime();
        open = true;
    }

    public synchronized LabObservation admit(String suppliedToken, String sessionId, String cohortId, String requestId) {
        expireIfNeeded();
        if (session == null) {
            // A stale experiment request must never become ordinary product traffic.
            if (sessionId != null || cohortId != null || requestId != null) throw new IllegalStateException("No active cohort");
            productRequests++;
            return null;
        }
        if (expired || !open || !authenticate(suppliedToken) || !session.equals(sessionId) || cohort == null
                || !cohort.id.equals(cohortId) || requestId == null
                || !requestId.matches("[a-f0-9-]{36}") || requestIds.size() >= cohort.expectedRequests
                || !requestIds.add(requestId)) {
            throw new IllegalStateException("Request not admitted to active cohort");
        }
        productRequests++;
        cohort.admitted.increment();
        return cohort;
    }

    public synchronized void finish(LabObservation observation, int status) {
        productRequests--;
        if (observation != null) {
            observation.completed.increment();
            if (status < 200 || status >= 300) observation.failures.increment();
        }
        expireIfNeeded();
    }

    public synchronized void close(String sessionId) {
        checkSession(sessionId);
        if (cohort == null) throw new IllegalStateException("No cohort");
        if (open) cohort.closedNanos = System.nanoTime();
        open = false;
    }

    public synchronized java.util.Map<String, Object> snapshot(String sessionId) {
        checkSession(sessionId);
        if (cohort == null || open) throw new IllegalStateException("Close admissions first");
        if (productRequests == 0 && cohort.activeSql.get() == 0 && cohort.drainedNanos == 0) {
            cohort.drainedNanos = System.nanoTime();
        }
        var result = cohort.snapshot();
        result.put("activeRequests", productRequests);
        result.put("drained", cohort.drainedNanos != 0);
        return result;
    }

    public synchronized boolean end(String sessionId, Runnable cleanup) {
        checkSession(sessionId);
        open = false;
        aborting = true;
        if (productRequests != 0 || preparingCohortId != null) return false;
        cleanup.run();
        session = null;
        cohort = null;
        requestIds.clear();
        cohortIds.clear();
        preparingCohortId = null;
        return true;
    }

    public synchronized void cancelPreparation(String sessionId, String cohortId) {
        if (session != null && session.equals(sessionId) && cohortId.equals(preparingCohortId)) {
            preparingCohortId = null;
            expireIfNeeded();
        }
    }

    private void checkSession(String id) {
        expireIfNeeded();
        if (session == null || expired || !session.equals(id)) throw new IllegalStateException("Invalid or expired session");
        touch();
    }

    private void touch() { deadline = clock.getAsLong() + LEASE_NANOS; }

    private void expireIfNeeded() {
        if (session != null && clock.getAsLong() - deadline >= 0) {
            expired = true;
            open = false;
            if (cohort != null && cohort.closedNanos == 0) cohort.closedNanos = System.nanoTime();
        }
        if (session != null && expired && productRequests == 0 && preparingCohortId == null) {
            session = null;
            cohort = null;
            requestIds.clear();
            cohortIds.clear();
            preparingCohortId = null;
        }
    }
}
