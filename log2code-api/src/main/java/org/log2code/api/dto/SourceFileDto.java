package org.log2code.api.dto;

/**
 * Response of {@code GET /api/sources/{fileId}} (T24 step 1): {@code {file_id, code_unit, module,
 * file_path, content, line_count}} (mirrors {@link org.log2code.core.model.SourceFile}, minus
 * {@code sha256}, which the controller uses for the {@code ETag} header instead of the body).
 */
public record SourceFileDto(String fileId, CodeUnitDto codeUnit, String module, String filePath, String content, int lineCount) {
}
