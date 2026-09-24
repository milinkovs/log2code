package org.log2code.ingester.stats;

import java.util.Map;

/** {@code stats --dataset <id>}: aggregations over an already-ingested dataset's {@code log2code-logs} documents. */
public record DatasetStats(
    String datasetId,
    long total,
    Map<String, Long> byStatus,
    Map<String, Long> byConfidenceLevel,
    Map<String, Long> byService,
    Map<String, Long> byLevel,
    long withException,
    long withTraceId
) {
}
