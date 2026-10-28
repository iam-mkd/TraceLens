package io.tracelens.telemetry;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationFilterTest {
    @Test
    void replacesUnsafeInputAndRestoresMdcEvenWhenRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER, "unsafe value\nforged log");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("correlationId", "previous");
        try {
            assertThatThrownBy(() -> new CorrelationFilter().doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("correlationId")).isEqualTo(response.getHeader(CorrelationFilter.HEADER));
                throw new IllegalStateException("test failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(response.getHeader(CorrelationFilter.HEADER)).matches("[a-f0-9-]{36}");
            assertThat(MDC.get("correlationId")).isEqualTo("previous");
        } finally {
            MDC.remove("correlationId");
        }
    }
}
