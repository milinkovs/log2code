package org.log2code.api.dto;

/** One outgoing call from a project method (mirrors {@link org.log2code.core.model.CallEdge}). */
public record CallEdgeDto(int line, String text, String targetMethodId, String targetFqn, boolean resolved, boolean viaInterface) {
}
