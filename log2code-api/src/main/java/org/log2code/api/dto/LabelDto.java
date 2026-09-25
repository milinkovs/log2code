package org.log2code.api.dto;

import java.time.Instant;

/** One manual label (mirrors {@link org.log2code.core.model.Label}, T25 step 2). */
public record LabelDto(
    String logId,
    String datasetId,
    String verdict,
    String correctStatementId,
    String predictedStatementId,
    String note,
    Instant labeledAt
) {
}
