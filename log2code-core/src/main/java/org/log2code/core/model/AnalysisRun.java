package org.log2code.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One analysis of one code unit (document {@code log2code-runs}, {@code _id = runId}). */
public record AnalysisRun(
    String runId,
    String kind,
    CodeUnit codeUnit,
    String repoUrl,
    String analyzerVersion,
    Instant startedAt,
    Instant finishedAt,
    long durationMs,
    Map<String, Object> stats,
    List<ModuleInfo> modules
) {
}
