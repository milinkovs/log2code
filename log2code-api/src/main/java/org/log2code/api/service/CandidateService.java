package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.log2code.api.dto.CandidateDetailDto;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.MatchResult;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;

/** Backs {@code GET /api/logs/{logId}/candidates} (T24 step 1): the top-5 candidates, joined with their catalog entries. */
public final class CandidateService {

    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public CandidateService(DocumentReader documentReader, IndexNames indexNames) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    public List<CandidateDetailDto> candidates(EnrichedLog log) {
        MatchResult match = log.match();
        List<Candidate> candidates = match == null || match.candidates() == null ? List.of() : match.candidates();
        return candidates.stream().map(this::toDetailDto).toList();
    }

    private CandidateDetailDto toDetailDto(Candidate candidate) {
        CatalogEntry entry = fetchCatalogEntry(candidate.statementId());
        if (entry == null) {
            return new CandidateDetailDto(candidate.statementId(), candidate.score(), null, null, null, null, null);
        }
        return new CandidateDetailDto(candidate.statementId(), candidate.score(), entry.classFqn(), entry.methodName(),
            entry.filePath(), entry.line(), entry.template());
    }

    private CatalogEntry fetchCatalogEntry(String statementId) {
        try {
            return documentReader.get(indexNames.catalog(), statementId, CatalogEntry.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
