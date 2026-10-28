package io.tracelens.lab;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class LabCoordinatorTest {
    private static final String TOKEN = "testonlytoken0123456789abcdefghij";
    private final AtomicLong time = new AtomicLong();
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("TRACELENS_LAB_TOKEN", TOKEN)
            .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/tracelens")
            .withProperty("spring.data.redis.host", "redis");

    private LabCoordinator coordinator() {
        environment.setActiveProfiles("lab");
        return new LabCoordinator(environment, time::get);
    }

    @Test
    void refusesUnapprovedTargetsAndMissingToken() {
        environment.setActiveProfiles("lab");
        environment.setProperty("spring.data.redis.host", "external.example");
        assertThatThrownBy(() -> new LabCoordinator(environment)).isInstanceOf(IllegalStateException.class);
        environment.setProperty("spring.data.redis.host", "redis");
        environment.setProperty("TRACELENS_LAB_TOKEN", "short");
        assertThatThrownBy(() -> new LabCoordinator(environment)).isInstanceOf(IllegalStateException.class);
        environment.setProperty("TRACELENS_LAB_TOKEN", TOKEN);
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/tracelens,external/db");
        assertThatThrownBy(() -> new LabCoordinator(environment)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void protectsSessionsFromConcurrentUnrelatedDuplicateAndLateRequests() {
        LabCoordinator c = coordinator();
        assertThat(c.authenticate(TOKEN)).isTrue();
        assertThat(c.authenticate("wrong")).isFalse();
        LabObservation ordinary = c.admit(null, null, null, null);
        assertThatThrownBy(c::start).isInstanceOf(IllegalStateException.class);
        c.finish(ordinary, 200);
        String session = c.start();
        assertThatThrownBy(c::start).isInstanceOf(IllegalStateException.class);
        LabObservation cohort = new LabObservation(UUID.randomUUID().toString(), "failure", 2, 100);
        c.begin(session, cohort.id);
        c.open(session, cohort);
        String first = UUID.randomUUID().toString();
        assertThatThrownBy(() -> c.admit(null, null, null, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.admit("wrong", session, cohort.id, first)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.admit(TOKEN, "stale-session", cohort.id, first)).isInstanceOf(IllegalStateException.class);
        assertThat(c.admit(TOKEN, session, cohort.id, first)).isSameAs(cohort);
        assertThatThrownBy(() -> c.admit(TOKEN, session, cohort.id, first)).isInstanceOf(IllegalStateException.class);
        c.close(session);
        assertThatThrownBy(() -> c.admit(TOKEN, session, cohort.id, UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
        assertThat(c.snapshot(session).get("drained")).isEqualTo(false);
        assertThatThrownBy(() -> c.begin(session, UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
        assertThat(c.end(session, () -> fail("must not clean up while active"))).isFalse();
        c.finish(cohort, 200);
        assertThat(c.snapshot(session).get("drained")).isEqualTo(true);
        assertThatThrownBy(() -> c.begin(session, cohort.id)).isInstanceOf(IllegalStateException.class);
        assertThat(c.end(session, () -> { })).isTrue();
        assertThatThrownBy(() -> c.admit(TOKEN, session, cohort.id, UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredRunnerKeepsGuardUntilServerWorkDrains() {
        LabCoordinator c = coordinator();
        String session = c.start();
        LabObservation cohort = new LabObservation(UUID.randomUUID().toString(), "failure", 1, 100);
        c.begin(session, cohort.id);
        c.open(session, cohort);
        c.admit(TOKEN, session, cohort.id, UUID.randomUUID().toString());
        time.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThatThrownBy(c::start).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.admit(null, null, null, null)).isInstanceOf(IllegalStateException.class);
        c.finish(cohort, 200);
        assertThatThrownBy(() -> c.snapshot(session)).isInstanceOf(IllegalStateException.class);
        assertThat(c.start()).isNotEqualTo(session);
    }

    @Test
    void preparationIsExclusiveAndCannotOutliveSessionGuard() {
        LabCoordinator c = coordinator();
        String session = c.start();
        String cohortId = UUID.randomUUID().toString();
        c.begin(session, cohortId);
        assertThatThrownBy(() -> c.begin(session, UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
        assertThat(c.end(session, () -> fail("cleanup during preparation"))).isFalse();
        assertThatThrownBy(c::start).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.open(session, new LabObservation(cohortId, "failure", 1, 100)))
                .isInstanceOf(IllegalStateException.class);
        c.cancelPreparation(session, cohortId);
        assertThat(c.end(session, () -> { })).isTrue();
    }

    @Test
    void expiredPreparationRetainsGuardUntilCleanupCompletes() {
        LabCoordinator c = coordinator();
        String session = c.start();
        String cohortId = UUID.randomUUID().toString();
        c.begin(session, cohortId);
        time.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThatThrownBy(c::start).isInstanceOf(IllegalStateException.class);
        c.cancelPreparation(session, cohortId);
        assertThat(c.start()).isNotEqualTo(session);
    }
}
