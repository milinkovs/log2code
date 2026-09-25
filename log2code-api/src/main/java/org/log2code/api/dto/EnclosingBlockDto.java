package org.log2code.api.dto;

/** Level 1 context (mirrors {@link org.log2code.core.model.EnclosingBlock}). */
public record EnclosingBlockDto(String blockKind, String condition, String branch, int startLine, int endLine) {
}
