package org.log2code.core.model;

/** One outgoing call from a project method (level 3, T13). */
public record CallEdge(
    int line,
    String text,
    String targetMethodId,
    String targetFqn,
    boolean resolved,
    boolean viaInterface
) {
}
