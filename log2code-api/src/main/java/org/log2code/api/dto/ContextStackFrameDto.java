package org.log2code.api.dto;

/**
 * One stack trace frame, resolved for {@link ContextBundleDto} (T25 step 1: mirrors
 * {@link StackFrameDto}, plus {@code snippet} — populated only for project frames, ±3 lines
 * around {@code line}, fetched from {@code log2code-sources} by {@code fileId}).
 */
public record ContextStackFrameDto(
    String className,
    String method,
    String file,
    Integer line,
    boolean inProject,
    String codeUnit,
    String fileId,
    String githubUrl,
    String snippet
) {
}
