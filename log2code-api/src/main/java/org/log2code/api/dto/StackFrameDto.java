package org.log2code.api.dto;

/** One stack trace frame (mirrors {@link org.log2code.core.model.StackFrame}). */
public record StackFrameDto(
    String className,
    String method,
    String file,
    Integer line,
    boolean inProject,
    String codeUnit,
    String fileId,
    String githubUrl
) {
}
