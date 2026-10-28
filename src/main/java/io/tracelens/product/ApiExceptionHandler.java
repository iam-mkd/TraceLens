package io.tracelens.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail notFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ProblemDetail dependencyUnavailable(DependencyUnavailableException ex) {
        // Do not log connection URLs, credentials, SQL, or driver exception messages.
        LOG.atWarn().addKeyValue("dependency", ex.dependency()).log("Product dependency unavailable");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "A required dependency is unavailable. Try again later.");
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("correlationId", MDC.get("correlationId"));
        return problem;
    }
}
