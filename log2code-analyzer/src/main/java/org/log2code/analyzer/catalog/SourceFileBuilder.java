package org.log2code.analyzer.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.SourceFile;

/**
 * Builds one {@link SourceFile} (0.7) for every {@code .java} file under a module's {@code
 * src/main/java} - independent of whether the file parsed successfully, since {@code log2code-sources}
 * stores raw file content, not AST-derived data. Takes the file's already-read bytes rather than
 * reading it itself, so callers that also need the exact source text for snippets (T10) read the file
 * only once.
 */
public final class SourceFileBuilder {

    private SourceFileBuilder() {
    }

    public static SourceFile build(byte[] bytes, FileInfo file, CodeUnit codeUnit) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        int lineCount = (int) content.lines().count();
        String sha256 = sha256Hex(bytes);
        String fileId = StableIds.fileId(codeUnit.name(), codeUnit.version(), file.filePath());
        return new SourceFile(fileId, codeUnit, file.module(), file.filePath(), content, lineCount, sha256);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
