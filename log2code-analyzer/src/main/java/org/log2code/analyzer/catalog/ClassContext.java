package org.log2code.analyzer.catalog;

/** The enclosing class of a log statement (0.8): {@code class_fqn} (dotted) and {@code class_binary}. */
public record ClassContext(String classFqn, String classBinary) {
}
