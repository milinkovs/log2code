package org.log2code.core.model;

/** One scored matching candidate (top 5 kept alongside the decision). */
public record Candidate(String statementId, double score) {
}
