package org.log2code.api.dto;

import java.time.Instant;

/** Response of {@code GET /api/logs/{logId}}: the whole enriched log document (T23 step 3). */
public record LogDetail(
    String logId,
    Instant timestamp,
    String timestampRaw,
    String datasetId,
    String sourceFile,
    int lineNumber,
    int lineCount,
    long sequence,
    String service,
    String module,
    String appName,
    String pid,
    String thread,
    String level,
    String loggerRaw,
    String logger,
    String message,
    String raw,
    String traceId,
    String spanId,
    ExceptionInfoDto exception,
    CodeVersionDto code,
    MatchResultDto match,
    GroundTruthDto groundTruth,
    String parserFormat,
    String ingesterVersion,
    Instant ingestedAt
) {
}
