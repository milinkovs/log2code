package org.log2code.api.dto;

/** One statement closest to a log statement on its path (mirrors {@link org.log2code.core.model.PrecedingStatement}). */
public record PrecedingStatementDto(String kind, String text, int line) {
}
