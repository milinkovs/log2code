package org.log2code.core.model;

/**
 * One of the closest statements on the path to a log statement (level 2, T11), nearest first: same
 * block before enclosing blocks. {@code kind} is one of {@code var_decl}/{@code assign}/{@code call}/
 * {@code return}/{@code throw}/{@code if}/{@code loop}/{@code other}.
 */
public record PrecedingStatement(String kind, String text, int line) {
}
