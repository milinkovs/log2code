package org.log2code.api.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.log2code.api.dto.LogDetail;
import org.log2code.api.dto.LogSearchResponse;
import org.log2code.api.service.LogMapper;
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

/** {@code /api/logs}: search and detail (T23 step 3). */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private final LogSearchService searchService;
    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public LogsController(LogSearchService searchService, DocumentReader documentReader, IndexNames indexNames) {
        this.searchService = searchService;
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
        EnrichedLog log;
        try {
            log = documentReader.get(indexNames.logs(), logId, EnrichedLog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (log == null) {
            throw new LogNotFoundException(logId);
        }
        return LogMapper.toDetail(log);
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
