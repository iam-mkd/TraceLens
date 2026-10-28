package io.tracelens.product;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(60)
class ProductApiIT {
    private static final String KEY = "tracelens:product:v1:1";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Container
    // Same PostgreSQL 18.3 manifest as Compose; digest-only form avoids tag@digest parser ambiguity.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres@sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7"));

    @Container
    // Same Redis 8.6.1 manifest as Compose.
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis@sha256:2afba59292f25f5d1af200496db41bea2c6c816b059f57ae74703a50a03a27d0"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl()
                + "&connectTimeout=2&socketTimeout=3&cancelSignalTimeout=1&options=-c%20statement_timeout%3D2000");
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meters;
    @Autowired JsonMapper json;

    @BeforeEach
    void evictOwnedKey() {
        redis.delete(KEY);
        redis.delete("tracelens:product:v1:999999");
    }

    @AfterAll
    static void closeHttpClient() {
        HTTP.close();
    }

    @Test
    void coldReadPopulatesCacheAndWarmReadAvoidsSql() throws Exception {
        double before = queries();
        HttpResponse<String> cold = get("/api/products/1");
        assertThat(cold.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(cold.body());
        assertThat(body.path("id").asLong()).isEqualTo(1);
        assertThat(body.path("name").asString()).isEqualTo("TraceLens Demo Product");
        assertThat(body.path("price").decimalValue()).isEqualByComparingTo("19.99");
        assertThat(body.path("currency").asString()).isEqualTo("USD");
        assertThat(queries() - before).isEqualTo(1);
        assertThat(redis.getExpire(KEY)).isBetween(1L, 300L);
        HttpResponse<String> warm = get("/api/products/1");
        assertThat(warm.statusCode()).isEqualTo(200);
        assertThat(json.readTree(warm.body())).isEqualTo(body);
        assertThat(queries() - before).isEqualTo(1);
    }

    @Test
    void expiredEntryCausesAnotherOriginRead() throws Exception {
        double before = queries();
        assertThat(get("/api/products/1").statusCode()).isEqualTo(200);
        redis.expire(KEY, Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(3)).until(() -> !redis.hasKey(KEY));
        assertThat(get("/api/products/1").statusCode()).isEqualTo(200);
        assertThat(queries() - before).isEqualTo(2);
    }

    @Test
    void missingProductIsNeverNegativelyCached() throws Exception {
        double before = queries();
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> response = get("/api/products/999999");
            assertProblem(response, 404);
        }
        assertThat(queries() - before).isEqualTo(2);
        assertThat(redis.hasKey("tracelens:product:v1:999999")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidIdsDoNotTouchOrigin(String id) throws Exception {
        double before = queries();
        assertProblem(get("/api/products/" + id), 400);
        assertThat(queries()).isEqualTo(before);
    }

    @Test
    void corruptedCacheReturnsExplicitErrorWithoutDatabaseFallback() throws Exception {
        redis.opsForValue().set(KEY, "invalid-json", Duration.ofSeconds(30));
        double before = queries();
        assertProblem(get("/api/products/1"), 503);
        assertThat(queries()).isEqualTo(before);
    }

    @Test
    void cacheWriteRejectionReturnsErrorAfterOriginReadAndRecovers() throws Exception {
        double before = queries();
        // Only this test's disposable Redis: GET still works, while SET is rejected with OOM.
        var limit = REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "1");
        assertThat(limit.getStdout()).contains("OK");
        try {
            assertProblem(get("/api/products/1"), 503);
            assertThat(queries() - before).isEqualTo(1);
        } finally {
            var reset = REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0");
            assertThat(reset.getStdout()).contains("OK");
        }
        assertThat(get("/api/products/1").statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentWarmRequestsPreserveResponsesAndAvoidSql() throws Exception {
        assertThat(get("/api/products/1").statusCode()).isEqualTo(200);
        double before = queries();
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                requests.add(() -> get("/api/products/1"));
            }
            for (var result : workers.invokeAll(requests)) {
                assertThat(result.get().statusCode()).isEqualTo(200);
            }
        }
        assertThat(queries()).isEqualTo(before);
    }

    @Test
    void pausedRedisFailsWithinDeadlineWithoutOriginFallbackAndRecovers() throws Exception {
        double before = queries();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertProblem(get("/api/products/1"), 503);
            assertThat(queries()).isEqualTo(before);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get("/api/products/1").statusCode()).isEqualTo(200));
    }

    @Test
    void pausedPostgresFailsWithinDeadlineAndReleasesActiveCount() throws Exception {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            assertProblem(get("/api/products/1"), 503);
            assertThat(meters.get("tracelens.product.sql.active").gauge().value()).isZero();
        } finally {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get("/api/products/1").statusCode()).isEqualTo(200));
    }

    @Test
    void readinessAndMetricsAreAvailableAndCorrelated() throws Exception {
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(200);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/products/1"))
                .header("X-Correlation-ID", "test-request-123").timeout(Duration.ofSeconds(8)).GET().build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).headers()
                .firstValue("X-Correlation-ID")).contains("test-request-123");
    }

    @Test
    void labControlsAreAbsentWithoutLabProfile() throws Exception {
        assertThat(get("/api/lab/cohorts?sessionId=anything").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private double queries() {
        return meters.get("tracelens.product.sql.attempts").counter().count();
    }

    private void assertProblem(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(json.readTree(response.body()).path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
        assertThat(response.body()).doesNotContain("jdbc:", "SQLException", "password", "stackTrace");
    }
}
