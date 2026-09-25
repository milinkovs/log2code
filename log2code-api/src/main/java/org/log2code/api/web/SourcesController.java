package org.log2code.api.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import org.log2code.api.dto.SourceFileDto;
import org.log2code.api.dto.SourceLookupResponse;
import org.log2code.api.service.SourceMapper;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.SourceFile;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/sources}: one source file, and looking one up by code unit + path (T24 step 1). */
@RestController
@RequestMapping("/api/sources")
public class SourcesController {

    /** {@code Cache-Control: max-age=31536000, immutable} (T24: content for a given {@code fileId} never changes). */
    private static final CacheControl SOURCE_CACHE_CONTROL = CacheControl.maxAge(365, TimeUnit.DAYS).immutable();

    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public SourcesController(DocumentReader documentReader, IndexNames indexNames) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<SourceFileDto> get(@PathVariable("fileId") String fileId) {
        SourceFile source = fetch(fileId);
        if (source == null) {
            throw new NotFoundException("source file not found: " + fileId);
        }
        return ResponseEntity.ok()
            .eTag("\"" + source.sha256() + "\"")
            .cacheControl(SOURCE_CACHE_CONTROL)
            .body(SourceMapper.toDto(source));
    }

    @GetMapping("/lookup")
    public SourceLookupResponse lookup(
        @RequestParam("codeUnit") String codeUnit,
        @RequestParam("version") String version,
        @RequestParam("path") String path
    ) {
        String fileId = StableIds.fileId(codeUnit, version, path);
        boolean exists;
        try {
            exists = documentReader.exists(indexNames.sources(), fileId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!exists) {
            throw new NotFoundException("no source file for codeUnit=" + codeUnit + ", version=" + version + ", path=" + path);
        }
        return new SourceLookupResponse(fileId);
    }

    private SourceFile fetch(String fileId) {
        try {
            return documentReader.get(indexNames.sources(), fileId, SourceFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
