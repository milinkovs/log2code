package org.log2code.core.model;

/** Level 1 context (T10): the block directly enclosing a log statement. */
public record EnclosingBlock(String blockKind, String condition, String branch, int startLine, int endLine) {
}
