package org.log2code.core.model;

/**
 * A sibling {@code if} (no {@code else}) before a log statement, in the same or an enclosing block,
 * whose then-branch unconditionally exits ({@code return}/{@code throw}/{@code continue}/{@code
 * break}) (level 2, T11). The log is reached only when {@code text} is false. {@code exitKind} is the
 * kind of the unconditional exit found in the then-branch.
 */
public record EarlyExit(String text, int line, String exitKind) {
}
