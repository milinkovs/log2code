package org.log2code.api.dto;

/** One enclosing construct on the path to a log statement (mirrors {@link org.log2code.core.model.Condition}). */
public record ConditionDto(String kind, String text, int line, boolean negated) {
}
