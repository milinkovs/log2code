package org.log2code.api.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.log2code.api.dto.LabelDto;
import org.log2code.api.dto.LabelRequest;
import org.log2code.api.dto.LabelSearchResponse;
import org.log2code.api.service.LabelListParams;
import org.log2code.api.service.LabelService;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/labels}: manual verdicts on a match, for evaluation (T25 step 2, odluka 31C). */
@RestController
@RequestMapping("/api/labels")
public class LabelsController {

    private final LabelService labelService;
    private final DocumentReader documentReader;
    private final IndexNames indexNames;

    public LabelsController(LabelService labelService, DocumentReader documentReader, IndexNames indexNames) {
        this.labelService = labelService;
        this.documentReader = documentReader;
        this.indexNames = indexNames;
    }

    @PutMapping("/{logId}")
    public LabelDto put(@PathVariable("logId") String logId, @RequestBody LabelRequest request) {
        return labelService.put(fetchOrThrow(logId), request);
    }

    @GetMapping("/{logId}")
    public LabelDto get(@PathVariable("logId") String logId) {
        LabelDto label = labelService.get(logId);
        if (label == null) {
            throw new NotFoundException("label not found: " + logId);
        }
        return label;
    }

    @DeleteMapping("/{logId}")
    public ResponseEntity<Void> delete(@PathVariable("logId") String logId) {
        labelService.delete(logId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public LabelSearchResponse list(
        @RequestParam(name = "datasetId", required = false) String datasetId,
        @RequestParam(name = "verdict", required = false) String verdict,
        @RequestParam(name = "size", defaultValue = "" + LabelListParams.DEFAULT_SIZE) int size,
        @RequestParam(name = "searchAfter", required = false) String searchAfter
    ) {
        return labelService.list(new LabelListParams(datasetId, verdict, size, searchAfter));
    }

    /** A label always corresponds to a real log event; PUT needs the log itself for {@code dataset_id}/{@code predicted_statement_id}. */
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
}
