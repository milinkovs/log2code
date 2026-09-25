package org.log2code.api.dto;

/** A guard exited before reaching the log statement (mirrors {@link org.log2code.core.model.EarlyExit}). */
public record EarlyExitDto(String text, int line, String exitKind) {
}
