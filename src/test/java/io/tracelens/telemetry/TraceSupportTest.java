package io.tracelens.telemetry;

import java.util.Optional;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceSupportTest {
    @Test
    void recordsCacheAndOriginAsChildrenAndMarksFailures() {
        GlobalOpenTelemetry.resetForTest();
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            OpenTelemetrySdk.builder().setTracerProvider(provider).buildAndRegisterGlobal();
            Span request = GlobalOpenTelemetry.getTracer("test").spanBuilder("request").startSpan();
            try (Scope scope = request.makeCurrent()) {
                assertThat(TraceSupport.cacheLookup("initial", () -> Optional.of("cached"))).contains("cached");
                assertThatThrownBy(() -> TraceSupport.operation("tracelens.origin.load", () -> {
                    throw new IllegalStateException("do not capture this message");
                })).isInstanceOf(IllegalStateException.class);
            } finally {
                request.end();
            }
            var spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(3);
            var cache = spans.stream().filter(span -> span.getName().equals("tracelens.cache.lookup")).findFirst().orElseThrow();
            var origin = spans.stream().filter(span -> span.getName().equals("tracelens.origin.load")).findFirst().orElseThrow();
            assertThat(cache.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
            assertThat(origin.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
            assertThat(cache.getAttributes().toString()).contains("initial", "true");
            assertThat(origin.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(origin.getEvents()).isEmpty();
        } finally {
            GlobalOpenTelemetry.resetForTest();
            exporter.close();
        }
    }
}
