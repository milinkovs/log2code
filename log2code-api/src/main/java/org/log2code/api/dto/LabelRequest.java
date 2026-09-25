package org.log2code.api.dto;

/**
 * Body of {@code PUT /api/labels/{logId}} (T25 step 2). {@code datasetId} and
 * {@code predictedStatementId} are not client-supplied: the server fills them in from the log
 * itself.
 */
public record LabelRequest(String verdict, String correctStatementId, String note) {
}
