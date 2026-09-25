package org.log2code.api.dto;

/**
 * The {@code code} section of {@link ContextBundleDto} (T25 step 1): the winning statement's file
 * and method, with the full method body ({@code methodSource}, extracted from the source file by
 * {@code methodStartLine}/{@code methodEndLine}) in addition to the catalog's short {@code snippet}.
 */
public record ContextCodeDto(
    String filePath,
    String githubUrl,
    String methodSource,
    int methodStartLine,
    String snippet,
    int snippetStartLine
) {
}
