package org.log2code.analyzer.catalog;

/**
 * The enclosing method-like unit of a log statement (0.7/0.8): {@code method_name},
 * {@code method_signature}, and the unit's own line range.
 */
public record MethodContext(String methodName, String methodSignature, int methodStartLine, int methodEndLine) {
}
