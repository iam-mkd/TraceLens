package io.tracelens.lab;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.tracelens.product.ProductRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LabApiIT.IsolatedContainers.class)
@ActiveProfiles("lab")
@Testcontainers
@Timeout(90)
class LabApiIT {
    private static final String TOKEN = "testonlytoken0123456789abcdefghij";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres@sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis@sha256:2afba59292f25f5d1af200496db41bea2c6c816b059f57ae74703a50a03a27d0")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("TRACELENS_LAB_TOKEN", () -> TOKEN);
        // The test-only beans below connect to disposable Testcontainers, while production
        // lab target validation continues to require the Compose service names.
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://postgres:5432/tracelens");
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", () -> "redis");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedContainers {
        @Bean DataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(POSTGRES.getJdbcUrl() + "&connectTimeout=2&socketTimeout=3&cancelSignalTimeout=1");
            source.setUsername(POSTGRES.getUsername());
            source.setPassword(POSTGRES.getPassword());
            source.setMaximumPoolSize(16);
            source.setMinimumIdle(0);
            source.setConnectionTimeout(1000);
            return source;
        }
        @Bean RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }
    }

    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @MockitoSpyBean ProductRepository repository;

    @AfterAll static void closeHttp() { HTTP.close(); }

    @Test
    void allModesUseIsolatedCountsAndSingleFlightOnRealServices() throws Exception {
        AtomicReference<CountDownLatch> gate = new AtomicReference<>();
        doAnswer(invocation -> {
            LabObservation current = LabContext.current();
            CountDownLatch barrier = gate.get();
            if (current != null && current.mode.equals("failure") && barrier != null) {
                barrier.countDown();
                if (!barrier.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out");
            }
            return invocation.callRealMethod();
        }).when(repository).findById(1);
        String session = post("/api/lab/session", Map.of(), TOKEN).path("sessionId").asString();
        try {
            JsonNode baseline = cohort(session, "baseline", 8, 100);
            assertThat(baseline.path("admitted").asInt()).isEqualTo(8);
            assertThat(baseline.path("initialHits").asInt()).isEqualTo(8);
            assertThat(baseline.path("sqlAttempts").asInt()).isZero();
            gate.set(new CountDownLatch(8));
            JsonNode failure = cohort(session, "failure", 8, 100);
            gate.set(null);
            assertThat(failure.path("initialMisses").asInt()).isEqualTo(8);
            assertThat(failure.path("sqlAttempts").asInt()).isEqualTo(8);
            assertThat(failure.path("peakSql").asInt()).isGreaterThan(1);
            assertThat(failure.path("sqlDurationNanos").size()).isEqualTo(8);
            for (int i = 0; i < 8; i++) {
                assertThat(failure.path("sqlDurationNanos").get(i).asLong())
                        .as("Synthetic delay is included in measured SQL")
                        .isGreaterThanOrEqualTo(Duration.ofMillis(50).toNanos());
            }
            JsonNode mitigation = cohort(session, "mitigation", 8, 100);
            assertThat(mitigation.path("sqlAttempts").asInt()).isEqualTo(1);
            assertThat(mitigation.path("sqlSuccesses").asInt()).isEqualTo(1);
            assertThat(mitigation.path("inFlightLoads").asInt()).isZero();
            assertThat(mitigation.path("admitted").asInt()).isEqualTo(8);
        } finally {
            gate.set(null);
            assertThat(post("/api/lab/end", Map.of("sessionId", session), TOKEN).path("ended").asBoolean()).isTrue();
        }
    }

    @Test
    void rejectsBadTokenUnrelatedAndDuplicateAndLateTraffic() throws Exception {
        assertThat(request("POST", "/api/lab/session", "{}", "incorrect", null, null).statusCode()).isEqualTo(401);
        String session = post("/api/lab/session", Map.of(), TOKEN).path("sessionId").asString();
        String cohort = UUID.randomUUID().toString();
        try {
            assertThat(request("GET", "/api/products/1", null, null, null, null).statusCode()).isEqualTo(409);
            assertThat(request("GET", "/api/products;ignored/1", null, null, null, null).statusCode()).isEqualTo(400);
            assertThat(request("POST", "/api/%6cab/session", "{}", null, null, null).statusCode()).isEqualTo(400);
            post("/api/lab/cohorts", Map.of("sessionId", session, "cohortId", cohort,
                    "mode", "baseline", "requests", 1, "delayMillis", 100), TOKEN);
            String requestId = UUID.randomUUID().toString();
            assertThat(request("GET", "/api/products/1", null, TOKEN, cohort, requestId, "stale-session").statusCode()).isEqualTo(409);
            assertThat(request("GET", "/api/products/1", null, TOKEN, cohort, requestId, session).statusCode()).isEqualTo(200);
            assertThat(request("GET", "/api/products/1", null, TOKEN, cohort, requestId, session).statusCode()).isEqualTo(409);
            post("/api/lab/close", Map.of("sessionId", session), TOKEN);
            assertThat(request("GET", "/api/products/1", null, TOKEN, cohort, UUID.randomUUID().toString(), session).statusCode()).isEqualTo(409);
            await().atMost(Duration.ofSeconds(5)).until(() -> getResult(session).path("drained").asBoolean());
            assertThat(request("POST", "/api/lab/cohorts", json.writeValueAsString(Map.of("sessionId", session,
                    "cohortId", cohort, "mode", "baseline", "requests", 1, "delayMillis", 100)), TOKEN, null, null).statusCode()).isEqualTo(409);
        } finally {
            assertThat(post("/api/lab/end", Map.of("sessionId", session), TOKEN).path("ended").asBoolean()).isTrue();
        }
        assertThat(request("GET", "/api/products/1", null, TOKEN, cohort, UUID.randomUUID().toString(), session).statusCode()).isEqualTo(409);
    }

    @Test
    void closingWithActiveWorkBlocksNewSessionsUntilDrained() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (LabContext.current() != null) {
                entered.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
            }
            return invocation.callRealMethod();
        }).when(repository).findById(1);
        String session = post("/api/lab/session", Map.of(), TOKEN).path("sessionId").asString();
        String cohort = UUID.randomUUID().toString();
        try (var worker = Executors.newSingleThreadExecutor()) {
            try {
                post("/api/lab/cohorts", Map.of("sessionId", session, "cohortId", cohort,
                        "mode", "failure", "requests", 1, "delayMillis", 100), TOKEN);
                Future<HttpResponse<String>> request = worker.submit(() ->
                        request("GET", "/api/products/1", null, TOKEN, cohort, UUID.randomUUID().toString(), session));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                post("/api/lab/close", Map.of("sessionId", session), TOKEN);
                assertThat(getResult(session).path("drained").asBoolean()).isFalse();
                assertThat(post("/api/lab/end", Map.of("sessionId", session), TOKEN).path("ended").asBoolean()).isFalse();
                assertThat(request("POST", "/api/lab/session", "{}", TOKEN, null, null).statusCode()).isEqualTo(409);
                assertThat(request("GET", "/api/products/1", null, null, null, null).statusCode()).isEqualTo(409);
                release.countDown();
                assertThat(request.get(8, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                await().atMost(Duration.ofSeconds(5)).until(() -> getResult(session).path("drained").asBoolean());
            } finally {
                release.countDown();
            }
        } finally {
            assertThat(post("/api/lab/end", Map.of("sessionId", session), TOKEN).path("ended").asBoolean()).isTrue();
        }
    }

    @Test
    void realCacheWriteFailureReleasesFollowersAndLaterCohortRecovers() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<LabObservation> leaderObservation = new AtomicReference<>();
        AtomicBoolean gateEnabled = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            LabObservation current = LabContext.current();
            if (current != null && gateEnabled.get()) {
                leaderObservation.set(current);
                loaded.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
            }
            return result;
        }).when(repository).findById(1);
        String session = post("/api/lab/session", Map.of(), TOKEN).path("sessionId").asString();
        String id = UUID.randomUUID().toString();
        boolean limited = false;
        try (var workers = Executors.newFixedThreadPool(8)) {
            try {
                post("/api/lab/cohorts", Map.of("sessionId", session, "cohortId", id,
                        "mode", "mitigation", "requests", 8, "delayMillis", 100), TOKEN);
                assertThat(REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "1").getStdout()).contains("OK");
                limited = true;
                CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
                List<Future<HttpResponse<String>>> requests = new ArrayList<>();
                for (int i = 0; i < 8; i++) requests.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timeout");
                    return request("GET", "/api/products/1", null, TOKEN, id, UUID.randomUUID().toString(), session);
                }));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(3)).until(() -> leaderObservation.get().waiters.sum() == 7);
                release.countDown();
                for (var response : requests) assertThat(response.get(8, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
                assertThat(REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0").getStdout()).contains("OK");
                limited = false;
                post("/api/lab/close", Map.of("sessionId", session), TOKEN);
                await().atMost(Duration.ofSeconds(5)).until(() -> getResult(session).path("drained").asBoolean());
                JsonNode failed = getResult(session);
                assertThat(failed.path("sqlAttempts").asInt()).isEqualTo(1);
                assertThat(failed.path("cacheWriteFailures").asInt()).isEqualTo(1);
                assertThat(failed.path("failures").asInt()).isEqualTo(8);
                assertThat(failed.path("inFlightLoads").asInt()).isZero();
                gateEnabled.set(false);
                JsonNode recovered = cohort(session, "mitigation", 8, 100);
                assertThat(recovered.path("sqlAttempts").asInt()).isEqualTo(1);
                assertThat(recovered.path("failures").asInt()).isZero();
            } finally {
                gateEnabled.set(false);
                release.countDown();
                if (limited) REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0");
            }
        } finally {
            assertThat(post("/api/lab/end", Map.of("sessionId", session), TOKEN).path("ended").asBoolean()).isTrue();
        }
    }

    private JsonNode cohort(String session, String mode, int count, int delay) throws Exception {
        String id = UUID.randomUUID().toString();
        JsonNode expected = post("/api/lab/cohorts", Map.of("sessionId", session, "cohortId", id,
                "mode", mode, "requests", count, "delayMillis", delay), TOKEN).path("expected");
        CountDownLatch ready = new CountDownLatch(count), release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timeout");
                return request("GET", "/api/products/1", null, TOKEN, id, UUID.randomUUID().toString(), session);
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var future : futures) {
                HttpResponse<String> response = future.get(12, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(json.readTree(response.body())).isEqualTo(expected);
            }
        } finally { release.countDown(); }
        post("/api/lab/close", Map.of("sessionId", session), TOKEN);
        await().atMost(Duration.ofSeconds(5)).until(() -> getResult(session).path("drained").asBoolean());
        return getResult(session);
    }

    private JsonNode getResult(String session) throws Exception {
        return json.readTree(request("GET", "/api/lab/cohorts?sessionId=" + session, null, TOKEN, null, null).body());
    }

    private JsonNode post(String path, Object body, String token) throws Exception {
        var response = request("POST", path, json.writeValueAsString(body), token, null, null);
        assertThat(response.statusCode()).as(path).isBetween(200, 299);
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    private HttpResponse<String> request(String method, String path, String body, String token, String cohort, String id) throws Exception {
        return request(method, path, body, token, cohort, id, null);
    }

    private HttpResponse<String> request(String method, String path, String body, String token, String cohort, String id, String session) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12));
        if (token != null) builder.header("X-TraceLens-Token", token);
        if (cohort != null) builder.header("X-TraceLens-Cohort", cohort);
        if (id != null) builder.header("X-TraceLens-Request", id);
        if (session != null) builder.header("X-TraceLens-Session", session);
        if (method.equals("POST")) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
