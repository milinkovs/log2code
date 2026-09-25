package org.log2code.api.dto;

/**
 * One of the top-5 matching candidates for a log event, joined with its catalog entry (T24 step 1:
 * {@code GET /api/logs/{logId}/candidates}). {@code classFqn}/{@code methodName}/{@code filePath}/
 * {@code line}/{@code template} are {@code null} if the candidate's {@code statementId} no longer
 * has a catalog entry (stale data).
 */
public record CandidateDetailDto(
    String statementId,
    double score,
    String classFqn,
    String methodName,
    String filePath,
    Integer line,
    String template
) {
}
