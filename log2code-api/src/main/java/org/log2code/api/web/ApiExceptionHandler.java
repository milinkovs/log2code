package org.log2code.api.web;

import java.io.UncheckedIOException;
import org.log2code.api.llm.explain.ExplainNotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 error responses for the two cases T23 requires: 404 for an unknown log id, 400 for bad parameters. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LogNotFoundException.class)
    public ProblemDetail handleNotFound(LogNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** T42: no Gemini key; {@code code} lets the UI tell this 503 apart from any other. */
    @ExceptionHandler(ExplainNotConfiguredException.class)
    public ProblemDetail handleLlmNotConfigured(ExplainNotConfiguredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setProperty("code", ExplainNotConfiguredException.CODE);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** OpenSearch itself failed (network, cluster, ...): not the client's fault, but not a 500 either. */
    @ExceptionHandler(UncheckedIOException.class)
    public ProblemDetail handleUpstreamFailure(UncheckedIOException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "OpenSearch request failed");
    }
}
