package org.log2code.core.model;

/** A boolean condition that guards reaching a log statement (level 2, T11). */
public record Condition(String expression, boolean negated, int line) {
}
