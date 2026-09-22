package org.log2code.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** One enriched log event (document {@code log2code-logs}, {@code _id = logId}). The original event is never modified; {@code raw} keeps it. */
public record EnrichedLog(
    String logId,
    @JsonProperty("@timestamp") Instant timestamp,
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
    Level level,
    String loggerRaw,
    String logger,
    String message,
    String raw,
    String traceId,
    String spanId,
    ExceptionInfo exception,
    CodeVersion code,
    MatchResult match,
    GroundTruth groundTruth,
    String parserFormat,
    String ingesterVersion,
    Instant ingestedAt
) {
}
