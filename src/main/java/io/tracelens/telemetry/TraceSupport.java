package io.tracelens.telemetry;

import java.util.Optional;
import java.util.function.Supplier;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/** The agent supplies the SDK; the API is a no-op when the agent is absent. */
public final class TraceSupport {
    private TraceSupport() { }

    public static Span start(String name) {
        return GlobalOpenTelemetry.getTracer("io.tracelens", "0.1.0").spanBuilder(name).startSpan();
    }

    public static <T> T operation(String name, Supplier<T> operation) {
        Span span = start(name);
        try (Scope scope = span.makeCurrent()) {
            return operation.get();
        } catch (RuntimeException | Error ex) {
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }

    public static <T> Optional<T> cacheLookup(String phase, Supplier<Optional<T>> operation) {
        Span span = start("tracelens.cache.lookup");
        span.setAttribute("tracelens.cache.phase", phase);
        try (Scope scope = span.makeCurrent()) {
            Optional<T> result = operation.get();
            span.setAttribute("tracelens.cache.hit", result.isPresent());
            return result;
        } catch (RuntimeException | Error ex) {
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }
}
