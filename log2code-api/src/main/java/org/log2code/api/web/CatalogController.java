package org.log2code.api.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.log2code.api.dto.CatalogEntryDto;
import org.log2code.api.service.CatalogMapper;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/catalog}: a single catalog entry with its control context (T24 step 1). */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public CatalogController(DocumentReader documentReader, IndexNames indexNames) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    @GetMapping("/{statementId}")
    public CatalogEntryDto get(@PathVariable("statementId") String statementId) {
        CatalogEntry entry;
        try {
            entry = documentReader.get(indexNames.catalog(), statementId, CatalogEntry.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (entry == null) {
            throw new NotFoundException("catalog entry not found: " + statementId);
        }
        return CatalogMapper.toDto(entry);
    }
}
