package org.log2code.api.dto;

/** One row of {@code GET /api/meta/datasets}: a dataset id with its event count. */
public record DatasetSummary(String datasetId, long count) {
}
