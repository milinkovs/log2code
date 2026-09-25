package org.log2code.api.dto;

import java.util.List;

/** Response of {@code GET /api/logs} (T23 step 3). */
public record LogSearchResponse(
    List<LogSummary> items,
    String nextSearchAfter,
    long total,
    long tookMs
) {
}
