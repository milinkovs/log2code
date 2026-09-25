package org.log2code.api.dto;

/** One caller of a project method, as recorded on the callee's own document (mirrors {@link org.log2code.core.model.CallerRef}). */
public record CallerRefDto(String methodId, String classFqn, String methodName, String fileId, int line) {
}
