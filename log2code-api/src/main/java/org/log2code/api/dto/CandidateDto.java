package org.log2code.api.dto;

/** One scored matching candidate (mirrors {@link org.log2code.core.model.Candidate}). */
public record CandidateDto(String statementId, double score) {
}
