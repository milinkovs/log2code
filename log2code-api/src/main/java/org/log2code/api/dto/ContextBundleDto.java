package org.log2code.api.dto;

import java.util.List;

/**
 * Response of {@code GET /api/logs/{logId}/context} (T25 step 1): everything a future LLM step
 * would need to explain a log event, assembled from the other endpoints of this API. Never
 * implements that LLM step itself (0.16) — {@code docs/context-bundle.md} has the full shape.
 */
public record ContextBundleDto(
    int schemaVersion,
    LogDetail log,
    ContextMatchDto match,
    CatalogEntryDto statement,
    ContextCodeDto code,
    ControlContextDto control,
    List<ContextCallerDto> callers,
    ContextExceptionDto exception,
    ContextNeighborsDto neighbors,
    TraceResponse trace
) {
    public static final int SCHEMA_VERSION = 1;
}
