package org.log2code.ingester.ingest;

import java.time.Instant;
import java.util.Map;

/**
 * The summary an {@code ingest} run prints and writes to {@code data/work/ingest/<dataset>/report.json}
 * (T21 step 5): event counts by status/confidence level/service/level, exception and trace-id counts,
 * and throughput.
 */
public record IngestReport(
    String datasetId,
    long totalEvents,
    Map<String, Long> byStatus,
    Map<String, Long> byConfidenceLevel,
    Map<String, Long> byService,
    Map<String, Long> byLevel,
    long withException,
    long withTraceId,
    long durationMs,
    double eventsPerSecond,
    Instant finishedAt
) {
}
