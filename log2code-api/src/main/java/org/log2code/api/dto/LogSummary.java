package org.log2code.api.dto;

import java.time.Instant;

/** One row of {@code GET /api/logs} (0.14: API DTOs, core model not exposed directly). */
public record LogSummary(
    String logId,
    Instant timestamp,
    String service,
    String level,
    String thread,
    String loggerRaw,
    String message,
    String status,
    Double confidence,
    String confidenceLevel,
    boolean hasException,
    String traceId,
    String classFqn,
    String methodName,
    Integer line
) {
}
