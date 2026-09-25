package org.log2code.api.service;

import org.log2code.api.dto.SourceFileDto;
import org.log2code.core.model.SourceFile;

/** Maps core {@link SourceFile} to {@link SourceFileDto} (T24 step 1: {@code sha256} stays out of the body, used for {@code ETag} instead). */
public final class SourceMapper {

    private SourceMapper() {
    }

    public static SourceFileDto toDto(SourceFile source) {
        return new SourceFileDto(source.fileId(), CatalogMapper.toCodeUnitDto(source.codeUnit()), source.module(), source.filePath(),
            source.content(), source.lineCount());
    }
}
