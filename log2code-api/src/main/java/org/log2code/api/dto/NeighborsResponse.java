package org.log2code.api.dto;

import java.util.List;

/** Response of {@code GET /api/logs/{logId}/neighbors} (T24 step 1). */
public record NeighborsResponse(List<LogSummary> before, LogSummary current, List<LogSummary> after) {
}
