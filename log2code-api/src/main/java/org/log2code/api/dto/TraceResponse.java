package org.log2code.api.dto;

import java.util.List;

/**
 * Response of {@code GET /api/logs/{logId}/trace} (T24 step 1). Always this shape, so a typed
 * client does not need to branch on the response's structure: {@code reason} is {@code null} when
 * {@code items} was found by {@code trace_id}, and {@code "no-trace-id"} when the log has none
 * (in which case {@code items} is always empty).
 */
public record TraceResponse(List<LogSummary> items, String reason) {

    public static final String REASON_NO_TRACE_ID = "no-trace-id";
}
