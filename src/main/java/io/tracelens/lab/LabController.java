package io.tracelens.lab;

import java.util.Map;
import io.tracelens.cache.ProductCache;
import io.tracelens.product.Product;
import io.tracelens.product.ProductService;
import io.opentelemetry.api.trace.Span;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("lab")
@RequestMapping("/api/lab")
public class LabController {
    private final LabCoordinator coordinator;
    private final ProductCache cache;
    private final ProductService products;
    private final Environment environment;

    public LabController(LabCoordinator coordinator, ProductCache cache, ProductService products, Environment environment) {
        this.coordinator = coordinator;
        this.cache = cache;
        this.products = products;
        this.environment = environment;
    }

    public record Session(String sessionId) { }
    public record Cohort(String sessionId, String cohortId, String mode, int requests, int delayMillis) { }

    @PostMapping("/session")
    public Map<String, Object> start() {
        return Map.of("sessionId", coordinator.start(), "tracing", Map.of(
                "configured", environment.getProperty("TRACELENS_TRACE_ENABLED", Boolean.class, false),
                "traceContextObserved", Span.current().getSpanContext().isValid(),
                "sampler", environment.getProperty("OTEL_TRACES_SAMPLER", "not_configured"),
                "exporter", environment.getProperty("OTEL_TRACES_EXPORTER", "not_configured"),
                "protocol", environment.getProperty("OTEL_EXPORTER_OTLP_PROTOCOL", "not_configured")));
    }

    @PostMapping("/cohorts")
    public Map<String, Object> prepare(@RequestBody Cohort input) {
        if (input.sessionId() == null || input.cohortId() == null || input.mode() == null
                || !input.cohortId().matches("[a-f0-9-]{36}")
                || !java.util.Set.of("baseline", "failure", "mitigation").contains(input.mode())
                || input.requests() < 1 || input.requests() > 64 || input.delayMillis() < 0 || input.delayMillis() > 500) {
            throw new IllegalArgumentException("Invalid cohort configuration");
        }
        coordinator.begin(input.sessionId(), input.cohortId());
        try {
            LabObservation observation = new LabObservation(input.cohortId(), input.mode(), input.requests(), input.delayMillis());
            cache.evict(1);
            Product expected = products.findById(1);
            if (!"baseline".equals(input.mode())) cache.evict(1);
            boolean present = cache.findById(1).isPresent();
            if (present != "baseline".equals(input.mode())) throw new IllegalStateException("Cache preparation failed");
            coordinator.open(input.sessionId(), observation);
            return Map.of("expected", expected);
        } finally {
            coordinator.cancelPreparation(input.sessionId(), input.cohortId());
        }
    }

    @PostMapping("/close")
    public void close(@RequestBody Session input) { coordinator.close(input.sessionId()); }

    @GetMapping("/cohorts")
    public Map<String, Object> results(@RequestParam String sessionId) {
        var snapshot = coordinator.snapshot(sessionId);
        snapshot.put("inFlightLoads", products.inFlightCount());
        if (products.inFlightCount() != 0) snapshot.put("drained", false);
        return snapshot;
    }

    @PostMapping("/results")
    public Map<String, Object> resultsByBody(@RequestBody Session input) {
        return results(input.sessionId());
    }

    @PostMapping("/end")
    public Map<String, Boolean> end(@RequestBody Session input) {
        return Map.of("ended", coordinator.end(input.sessionId(), () -> cache.evict(1)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict() { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Lab state conflict"); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid() { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid lab request"); }
}
