package org.log2code.api.dto;

import java.time.Instant;

/** One row of {@code GET /api/meta/code-units}, from {@code log2code-runs}. */
public record CodeUnitSummary(String kind, String name, String version, Instant finishedAt) {
}
