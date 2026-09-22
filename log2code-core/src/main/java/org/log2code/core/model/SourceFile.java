package org.log2code.core.model;

/** One source file (document {@code log2code-sources}, {@code _id = fileId}). */
public record SourceFile(
    String fileId,
    CodeUnit codeUnit,
    String module,
    String filePath,
    String content,
    int lineCount,
    String sha256
) {
}
