package io.tracelens.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One-shot Compose runner; target and report directory are deliberately fixed. */
public final class ExperimentRunner {
    private static final String BASE = "http://app:8080";
    private static final Path REPORTS = Path.of("/reports");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(8);
    private final JsonMapper json = JsonMapper.builder().build();
    private final String token = System.getenv("TRACELENS_LAB_TOKEN");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).build();

    private record Config(int concurrency, int repetitions, int delayMillis) { }
    private record Sample(String requestId, String traceId, long dispatchOffsetNanos, long elapsedNanos, String outcome, int status) { }

    public int run(String[] args) {
        String runId = UUID.randomUUID().toString();
        List<Map<String, Object>> cohorts = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("runId", runId);
        report.put("startedAt", Instant.now().toString());
        report.put("hypothesis", "Concurrent misses amplify origin reads; single-instance coalescing reduces a successful refill to one read.");
        report.put("cohorts", cohorts);
        report.put("limitations", List.of("One instance and one synthetic product; local single-flight does not coordinate replicas.",
                "Explicit invalidation models the refill race after expiry; it does not measure expiry scheduling.",
                "The PostgreSQL delay is synthetic; burst completion rate is not sustained throughput.",
                "A client barrier does not guarantee simultaneous server arrivals; small samples make P99 an extreme observation.",
                "Runner and application share host resources; client-side SQL overlap includes network time."));
        String session = null;
        String outcome = "INVALID";
        String reason = null;
        try {
            Config config = parse(args);
            if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}")) {
                throw new IllegalArgumentException("Set a high-entropy TRACELENS_LAB_TOKEN of 32-128 URL-safe characters");
            }
            Files.createDirectories(REPORTS);
            report.put("configuration", Map.of("productId", 1, "concurrency", config.concurrency(),
                    "repetitionsPerMode", config.repetitions(), "originDelayMillis", config.delayMillis(),
                    "httpDeadlineMillis", 5000, "drainDeadlineMillis", 10000, "cacheTtlSeconds", 300,
                    "databasePoolSize", 16, "maximumServerThreads", 64));
            report.put("environment", Map.of("applicationVersion", "0.1.0-SNAPSHOT", "springBootVersion", "4.1.2",
                    "postgresImage", "18.3-alpine", "redisImage", "8.6.1-alpine",
                    "runnerJava", System.getProperty("java.version"), "runnerOs", System.getProperty("os.name"),
                    "runnerArchitecture", System.getProperty("os.arch"), "runnerAvailableProcessors", Runtime.getRuntime().availableProcessors()));
            warmConnections(config.concurrency());
            JsonNode sessionResponse = post("/api/lab/session", Map.of());
            session = sessionResponse.path("sessionId").asString();
            report.put("applicationTracing", sessionResponse.path("tracing"));
            if (session.isBlank()) throw new IOException("Lab did not return a session identifier");
            String[] modes = {"baseline", "failure", "mitigation"};
            for (int iteration = 0; iteration < config.repetitions(); iteration++) {
                for (int step = 0; step < modes.length; step++) {
                    String mode = modes[(iteration + step) % modes.length];
                    Map<String, Object> result;
                    try {
                        result = runCohort(runId, session, mode, config, iteration + 1);
                    } catch (Exception ex) {
                        cohorts.add(Map.of("mode", mode, "repetition", iteration + 1,
                                "outcome", "INVALID", "invalidReason", "Cohort execution or collection failed"));
                        throw ex;
                    }
                    cohorts.add(result);
                    if ("INVALID".equals(result.get("outcome"))) throw new IOException("Cohort invalid; later cohorts were not run");
                }
            }
            boolean failed = cohorts.stream().anyMatch(c -> "FAILED".equals(c.get("outcome")));
            boolean inconclusive = cohorts.stream().anyMatch(c -> "INCONCLUSIVE".equals(c.get("outcome")));
            outcome = failed ? "FAILED" : inconclusive ? "INCONCLUSIVE" : "PASSED";
        } catch (Exception ex) {
            reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            outcome = "INVALID";
        } finally {
            if (session != null) {
                try {
                    JsonNode result = post("/api/lab/end", Map.of("sessionId", session));
                    if (!result.path("ended").asBoolean()) {
                        outcome = "INVALID";
                        reason = "Session still has active requests; lease guard remains until they drain";
                    }
                } catch (Exception ex) {
                    outcome = "INVALID";
                    reason = "Could not end and clean up the lab session";
                }
            }
            http.close();
        }
        report.put("finishedAt", Instant.now().toString());
        report.put("outcome", outcome);
        boolean contextObserved = false;
        for (Map<String, Object> cohort : cohorts) {
            if (cohort.get("samples") instanceof List<?> samples) {
                for (Object item : samples) {
                    if (item instanceof Sample sample && sample.traceId() != null) contextObserved = true;
                }
            }
        }
        Map<String, Object> tracing = new LinkedHashMap<>();
        tracing.put("configured", "true".equalsIgnoreCase(System.getenv("TRACELENS_TRACE_ENABLED")));
        tracing.put("traceContextObserved", contextObserved);
        tracing.put("delivery", "NOT_VERIFIED");
        tracing.put("sampler", System.getenv().getOrDefault("OTEL_TRACES_SAMPLER", "not_configured"));
        tracing.put("exporter", System.getenv().getOrDefault("OTEL_TRACES_EXPORTER", "not_configured"));
        tracing.put("protocol", System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_PROTOCOL", "not_configured"));
        tracing.put("agentVersion", "true".equalsIgnoreCase(System.getenv("TRACELENS_TRACE_ENABLED")) ? "2.33.1 (configured)" : "not_configured");
        report.put("tracing", tracing);
        if (reason != null) report.put("reason", reason);
        try {
            Files.createDirectories(REPORTS);
            Path jsonPath = REPORTS.resolve(runId + ".json");
            Path markdownPath = REPORTS.resolve(runId + ".md");
            Files.writeString(jsonPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Files.writeString(markdownPath, markdown(report, cohorts));
            System.out.println("Outcome: " + outcome + "; reports: " + jsonPath + " and " + markdownPath);
        } catch (Exception ex) {
            System.err.println("Could not write reports to /reports: " + ex.getClass().getSimpleName());
            return 1;
        }
        return switch (outcome) { case "PASSED" -> 0; case "INCONCLUSIVE" -> 2; default -> 1; };
    }

    private Config parse(String[] args) {
        int concurrency = 32, repetitions = 5, delay = 100;
        if (args.length % 2 != 0) throw new IllegalArgumentException("Options require values");
        for (int i = 0; i < args.length; i += 2) {
            int value = Integer.parseInt(args[i + 1]);
            switch (args[i]) {
                case "--concurrency" -> concurrency = value;
                case "--repetitions" -> repetitions = value;
                case "--delay-ms" -> delay = value;
                default -> throw new IllegalArgumentException("Unknown experiment option");
            }
        }
        if (concurrency < 1 || concurrency > 64 || repetitions < 1 || repetitions > 20 || delay < 0 || delay > 500) {
            throw new IllegalArgumentException("Bounds: concurrency 1-64, repetitions 1-20, delay 0-500 ms");
        }
        return new Config(concurrency, repetitions, delay);
    }

    private void warmConnections(int count) throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(count)) {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) results.add(workers.submit(() -> {
                ready.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Warmup barrier timed out");
                return http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/products/1"))
                        .timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            }));
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("Warmup workers did not become ready");
            release.countDown();
            for (Future<Integer> result : results) if (result.get(8, TimeUnit.SECONDS) != 200) throw new IOException("Warmup failed");
        }
    }

    private Map<String, Object> runCohort(String runId, String session, String mode, Config config, int repetition) throws Exception {
        String cohortId = UUID.randomUUID().toString();
        JsonNode expected = post("/api/lab/cohorts", Map.of("sessionId", session, "cohortId", cohortId,
                "mode", mode, "requests", config.concurrency(), "delayMillis", config.delayMillis())).path("expected");
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger dispatched = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(config.concurrency());
        CountDownLatch release = new CountDownLatch(1);
        long[] releaseNanos = new long[1];
        boolean workersDone = true;
        ExecutorService workers = Executors.newFixedThreadPool(config.concurrency());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < config.concurrency(); i++) futures.add(workers.submit(() -> {
                ready.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) return;
                    String requestId = UUID.randomUUID().toString();
                    Span span = GlobalOpenTelemetry.getTracer("io.tracelens.runner", "0.1.0")
                            .spanBuilder("tracelens.experiment.request").setNoParent().startSpan();
                    span.setAttribute("tracelens.run.id", runId);
                    span.setAttribute("tracelens.cohort.id", cohortId);
                    span.setAttribute("tracelens.cohort.mode", mode);
                    span.setAttribute("tracelens.request.id", requestId);
                    String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
                    HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/api/products/1"))
                            .header("X-TraceLens-Token", token).header("X-TraceLens-Session", session)
                            .header("X-TraceLens-Cohort", cohortId)
                            .header("X-TraceLens-Run", runId)
                            .header("X-TraceLens-Request", requestId).header("X-Correlation-ID", requestId)
                            .timeout(REQUEST_TIMEOUT).GET().build();
                    Scope scope = span.makeCurrent();
                    long start = System.nanoTime();
                    dispatched.incrementAndGet();
                    String result = "ERROR";
                    int status = 0;
                    long completedAt = 0;
                    try {
                        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        completedAt = System.nanoTime();
                        status = response.statusCode();
                        result = status == 200 && json.readTree(response.body()).equals(expected) ? "SUCCESS" : "FAILURE";
                    } catch (HttpTimeoutException ex) {
                        span.setStatus(StatusCode.ERROR);
                        result = "TIMEOUT";
                    } catch (InterruptedException ex) {
                        span.setStatus(StatusCode.ERROR);
                        Thread.currentThread().interrupt();
                        result = "INTERRUPTED";
                    } catch (Exception ex) {
                        span.setStatus(StatusCode.ERROR);
                        result = "ERROR";
                    } finally {
                        long end = completedAt == 0 ? System.nanoTime() : completedAt;
                        if (!"SUCCESS".equals(result)) span.setStatus(StatusCode.ERROR);
                        samples.add(new Sample(requestId, traceId, start - releaseNanos[0], end - start, result, status));
                        try { scope.close(); } finally { span.end(); }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("Cohort workers did not become ready");
            releaseNanos[0] = System.nanoTime();
            release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            for (Future<?> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { workersDone = false; break; }
                try { future.get(remaining, TimeUnit.NANOSECONDS); }
                catch (java.util.concurrent.TimeoutException ex) { workersDone = false; break; }
            }
            if (!workersDone) for (Future<?> future : futures) future.cancel(true);
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            post("/api/lab/close", Map.of("sessionId", session));
        }
        JsonNode server = null;
        long drainDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            server = post("/api/lab/results", Map.of("sessionId", session));
            if (server.path("drained").asBoolean()) break;
            Thread.sleep(100);
        } while (System.nanoTime() < drainDeadline);
        List<Sample> stableSamples = List.copyOf(samples);
        long successes = stableSamples.stream().filter(s -> s.outcome().equals("SUCCESS")).count();
        long timeouts = stableSamples.stream().filter(s -> s.outcome().equals("TIMEOUT")).count();
        boolean valid = workersDone && server.path("drained").asBoolean() && dispatched.get() == config.concurrency()
                && stableSamples.size() == config.concurrency() && successes == config.concurrency()
                && server.path("admitted").asLong() == config.concurrency()
                && server.path("completed").asLong() == config.concurrency()
                && server.path("failures").asLong() == 0 && server.path("inFlightLoads").asInt() == 0;
        long queries = server.path("sqlAttempts").asLong();
        String outcome;
        if (!valid) outcome = "INVALID";
        else if (mode.equals("baseline")) outcome = queries == 0 ? "PASSED" : "FAILED";
        else if (mode.equals("mitigation")) outcome = queries == 1 && server.path("sqlSuccesses").asLong() == 1 ? "PASSED" : "FAILED";
        else outcome = queries > 1 && server.path("peakSql").asInt() > 1 ? "PASSED" : "INCONCLUSIVE";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cohortId", cohortId);
        result.put("mode", mode);
        result.put("repetition", repetition);
        result.put("outcome", outcome);
        result.put("valid", valid);
        result.put("scheduled", config.concurrency());
        result.put("dispatched", dispatched.get());
        result.put("successes", successes);
        result.put("timeouts", timeouts);
        result.put("failures", stableSamples.size() - successes - timeouts);
        result.put("samples", stableSamples);
        long duration = stableSamples.stream().mapToLong(s -> s.dispatchOffsetNanos() + s.elapsedNanos()).max().orElse(0);
        result.put("cohortDurationNanos", duration);
        result.put("cohortCompletionsPerSecond", duration == 0 ? null : stableSamples.size() * 1_000_000_000.0 / duration);
        result.put("allTerminalLatencyNanos", percentiles(stableSamples.stream().map(Sample::elapsedNanos).toList()));
        result.put("successfulLatencyNanos", percentiles(stableSamples.stream().filter(s -> s.outcome().equals("SUCCESS")).map(Sample::elapsedNanos).toList()));
        result.put("server", server);
        Map<String, Object> assertions = new LinkedHashMap<>();
        assertions.put("allResponsesCorrect", successes == config.concurrency());
        assertions.put("allScheduledRequestsAdmitted", server.path("admitted").asLong() == config.concurrency());
        assertions.put("serverDrained", server.path("drained").asBoolean());
        assertions.put("noInFlightLoads", server.path("inFlightLoads").asInt() == 0);
        assertions.put("expectedOriginReads", mode.equals("baseline") ? "zero" : mode.equals("mitigation") ? "exactly one successful read" : "more than one overlapping read");
        assertions.put("observedOriginReads", queries);
        assertions.put("originReadConditionMet", valid ? outcome.equals("PASSED") : null);
        result.put("assertions", assertions);
        result.put("readsPerAdmittedRequest", server.path("admitted").asLong() == 0 ? null : (double) queries / server.path("admitted").asLong());
        if (!mode.equals("baseline") && valid) {
            result.put("refillAmplification", queries);
            result.put("excessReads", Math.max(0, queries - 1));
        }
        if (!valid) result.put("invalidReason", "Incomplete, unsuccessful, or undrained cohort; inspect request samples and server counts");
        return result;
    }

    static Map<String, Object> percentiles(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", sorted.size());
        for (int percentile : new int[]{50, 95, 99}) {
            result.put("p" + percentile, sorted.isEmpty() ? null : sorted.get((int) Math.ceil(percentile * sorted.size() / 100.0) - 1));
        }
        return result;
    }

    private JsonNode post(String path, Object body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(BASE + path)).header("X-TraceLens-Token", token)
                .header("Content-Type", "application/json").timeout(CONTROL_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("Lab control returned HTTP " + response.statusCode());
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    private String markdown(Map<String, Object> report, List<Map<String, Object>> rows) {
        StringBuilder result = new StringBuilder("# TraceLens cache-stampede report\n\n");
        result.append("Run: ").append(report.get("runId")).append("  \nOutcome: **").append(report.get("outcome")).append("**\n\n");
        result.append(report.get("hypothesis")).append("\n\nConfiguration: `").append(report.get("configuration")).append("`\n\n");
        result.append("Runner tracing: `").append(report.get("tracing")).append("`. Application tracing: `")
                .append(report.get("applicationTracing")).append("`. Delivery is not verified by the runner.\n\n");
        result.append("| Mode | Repetition | Outcome | Origin reads | Peak outstanding SQL | P50 / P95 / P99 (ns) | Samples |\n| --- | ---: | --- | ---: | ---: | --- | ---: |\n");
        for (Map<String, Object> row : rows) {
            JsonNode server = (JsonNode) row.get("server");
            Map<?, ?> p = (Map<?, ?>) row.get("allTerminalLatencyNanos");
            if (server == null || p == null) {
                result.append("| ").append(row.get("mode")).append(" | ").append(row.get("repetition"))
                        .append(" | INVALID | — | — | — | 0 |\n");
                continue;
            }
            result.append("| ").append(row.get("mode")).append(" | ").append(row.get("repetition")).append(" | ")
                    .append(row.get("outcome")).append(" | ").append(server.path("sqlAttempts").asLong()).append(" | ")
                    .append(server.path("peakSql").asInt()).append(" | ").append(p.get("p50")).append(" / ")
                    .append(p.get("p95")).append(" / ").append(p.get("p99")).append(" | ").append(p.get("sampleCount")).append(" |\n");
        }
        result.append("\nExample traces (Jaeger storage is transient):\n\n");
        for (Map<String, Object> row : rows) {
            if (row.get("samples") instanceof List<?> samples) {
                samples.stream().filter(Sample.class::isInstance).map(Sample.class::cast)
                        .filter(sample -> sample.traceId() != null).findFirst().ifPresent(sample ->
                                result.append("- ").append(row.get("mode")).append(" cohort ").append(row.get("repetition"))
                                        .append(": http://127.0.0.1:16686/trace/").append(sample.traceId()).append("\n"));
            }
        }
        if (report.containsKey("reason")) result.append("\nReason: ").append(report.get("reason")).append("\n");
        result.append("\nPercentiles use nearest-rank; details, successful-only latency, raw samples, failures, and configuration are in the JSON report.\n\n");
        result.append("Limitations: one instance, one synthetic key, synthetic PostgreSQL delay, and shared host resources. ")
                .append("Invalidation models the refill race, and P99 from a small burst is an extreme observation. ")
                .append("No production performance or sustained-throughput claim is made.\n");
        return result.toString();
    }
}
