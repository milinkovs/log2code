package org.log2code.core.model;

import java.time.Instant;

/** One manual label (document {@code log2code-labels}, {@code _id = logId}). */
public record Label(
    String logId,
    String datasetId,
    String verdict,
    String correctStatementId,
    String predictedStatementId,
    String note,
    Instant labeledAt
) {
    public static final String VERDICT_CORRECT = "correct";
    public static final String VERDICT_INCORRECT = "incorrect";
    public static final String VERDICT_NOT_IN_CATALOG = "not_in_catalog";
}
