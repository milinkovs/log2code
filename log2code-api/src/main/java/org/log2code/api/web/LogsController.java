package org.log2code.api.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.log2code.api.dto.CandidateDetailDto;
import org.log2code.api.dto.ContextBundleDto;
import org.log2code.api.dto.LogDetail;
import org.log2code.api.dto.LogSearchResponse;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.dto.TraceResponse;
import org.log2code.api.service.CandidateService;
import org.log2code.api.service.ContextBundleService;
import org.log2code.api.service.LogMapper;
import org.log2code.api.service.LogNeighborhoodService;
import org.log2code.api.service.LogSearchParams;
import org.log2code.api.service.LogSearchService;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/logs}: search, detail, candidates, neighbors and trace (T23 step 3, T24 step 1). */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private final LogSearchService searchService;
    private final CandidateService candidateService;
    private final LogNeighborhoodService neighborhoodService;
    private final ContextBundleService contextBundleService;
    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public LogsController(LogSearchService searchService, CandidateService candidateService,
            LogNeighborhoodService neighborhoodService, ContextBundleService contextBundleService,
            DocumentReader documentReader, IndexNames indexNames) {
        this.searchService = searchService;
        this.candidateService = candidateService;
        this.neighborhoodService = neighborhoodService;
        this.contextBundleService = contextBundleService;
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    @GetMapping
    public LogSearchResponse search(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "service", required = false) List<String> service,
        @RequestParam(name = "level", required = false) List<String> level,
        @RequestParam(name = "status", required = false) List<String> status,
        @RequestParam(name = "confidence", required = false) List<String> confidence,
        @RequestParam(name = "datasetId", required = false) String datasetId,
        @RequestParam(name = "from", required = false) String from,
        @RequestParam(name = "to", required = false) String to,
        @RequestParam(name = "traceId", required = false) String traceId,
        @RequestParam(name = "hasException", required = false) Boolean hasException,
        @RequestParam(name = "statementId", required = false) String statementId,
        @RequestParam(name = "size", defaultValue = "" + LogSearchParams.DEFAULT_SIZE) int size,
        @RequestParam(name = "searchAfter", required = false) String searchAfter,
        @RequestParam(name = "order", required = false) String order
    ) {
        LogSearchParams params = new LogSearchParams(q, service, level, status, confidence, datasetId,
            parseInstant("from", from), parseInstant("to", to), traceId, hasException, statementId,
            size, searchAfter, order);
        return searchService.search(params);
    }

    @GetMapping("/{logId}")
    public LogDetail get(@PathVariable("logId") String logId) {
        return LogMapper.toDetail(fetchOrThrow(logId));
    }

    @GetMapping("/{logId}/candidates")
    public List<CandidateDetailDto> candidates(@PathVariable("logId") String logId) {
        return candidateService.candidates(fetchOrThrow(logId));
    }

    @GetMapping("/{logId}/neighbors")
    public NeighborsResponse neighbors(
        @PathVariable("logId") String logId,
        @RequestParam(name = "before", defaultValue = "" + LogNeighborhoodService.DEFAULT_BEFORE) int before,
        @RequestParam(name = "after", defaultValue = "" + LogNeighborhoodService.DEFAULT_AFTER) int after,
        @RequestParam(name = "scope", required = false) String scope
    ) {
        return neighborhoodService.neighbors(fetchOrThrow(logId), before, after, scope);
    }

    @GetMapping("/{logId}/trace")
    public TraceResponse trace(
        @PathVariable("logId") String logId,
        @RequestParam(name = "limit", defaultValue = "" + LogNeighborhoodService.DEFAULT_TRACE_LIMIT) int limit
    ) {
        return neighborhoodService.trace(fetchOrThrow(logId), limit);
    }

    @GetMapping("/{logId}/context")
    public ContextBundleDto context(
        @PathVariable("logId") String logId,
        @RequestParam(name = "neighbors", defaultValue = "" + ContextBundleService.DEFAULT_NEIGHBORS) int neighbors
    ) {
        return contextBundleService.build(fetchOrThrow(logId), neighbors);
    }

    private EnrichedLog fetchOrThrow(String logId) {
        EnrichedLog log;
        try {
            log = documentReader.get(indexNames.logs(), logId, EnrichedLog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (log == null) {
            throw new LogNotFoundException(logId);
        }
        return log;
    }

    private static Instant parseInstant(String paramName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(paramName + " must be an ISO-8601 instant: " + value, e);
        }
    }
}
