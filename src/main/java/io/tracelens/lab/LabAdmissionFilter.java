package io.tracelens.lab;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.opentelemetry.api.trace.Span;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class LabAdmissionFilter extends OncePerRequestFilter {
    private final LabCoordinator coordinator;
    public LabAdmissionFilter(LabCoordinator coordinator) { this.coordinator = coordinator; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        String rawPath = request.getRequestURI();
        if (path.startsWith("/api/") && (rawPath.contains("%") || rawPath.contains(";"))) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (coordinator.enabled() && path.startsWith("/api/lab/")) {
            if (!coordinator.authenticate(request.getHeader("X-TraceLens-Token"))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!path.startsWith("/api/products/")) {
            chain.doFilter(request, response);
            return;
        }
        LabObservation observation;
        try {
            observation = coordinator.admit(request.getHeader("X-TraceLens-Token"),
                    request.getHeader("X-TraceLens-Session"),
                    request.getHeader("X-TraceLens-Cohort"), request.getHeader("X-TraceLens-Request"));
        } catch (IllegalStateException ex) {
            response.sendError(HttpServletResponse.SC_CONFLICT);
            return;
        }
        if (observation != null) {
            LabContext.install(observation);
            Span span = Span.current();
            String runId = request.getHeader("X-TraceLens-Run");
            if (runId != null && runId.matches("[a-f0-9-]{36}")) span.setAttribute("tracelens.run.id", runId);
            span.setAttribute("tracelens.cohort.id", observation.id);
            span.setAttribute("tracelens.cohort.mode", observation.mode);
            span.setAttribute("tracelens.request.id", request.getHeader("X-TraceLens-Request"));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            LabContext.clear();
            coordinator.finish(observation, response.getStatus());
        }
    }
}
