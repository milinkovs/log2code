package org.log2code.core.model;

/**
 * One enclosing construct on the path from the method body down to a log statement (level 2, T11),
 * outside-in. {@code kind} is one of {@code if}/{@code else}/{@code loop}/{@code switch_case}/
 * {@code try}/{@code catch}/{@code finally}/{@code lambda}/{@code synchronized}; only {@code else}
 * sets {@code negated} (it carries the guarding {@code if}'s own condition as {@code text}).
 */
public record Condition(String kind, String text, int line, boolean negated) {
}
