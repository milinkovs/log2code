package org.log2code.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * One parsed and assembled log event, before matching (T17/T18 output, T20 input).
 * {@code log_id} is derived on demand via {@code StableIds.logId(datasetId, sourceFile, lineNumber)}.
 */
public record LogEvent(
    String datasetId,
    String sourceFile,
    int lineNumber,
    int lineCount,
    long sequence,
    @JsonProperty("@timestamp") Instant timestamp,
    String timestampRaw,
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
    GroundTruth groundTruth,
    String parserFormat
) {
}
