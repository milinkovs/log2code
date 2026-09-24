package org.log2code.ingester.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.LogEvent;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.ingester.IngesterUserException;
import org.log2code.ingester.assemble.AssemblyContext;
import org.log2code.ingester.assemble.EventAssembler;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.enrich.StackFrameResolver;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.match.Matcher;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * T21's ingest pipeline: for every file the manifest lists, assembles events (T18), matches each one
 * against the catalog (T20), denormalizes the winning statement's fields, resolves its exception's
 * stack frames (this task's own "Rezolucija stack frame-ova" step) and writes the enriched result to
 * {@code log2code-logs} - idempotently, since {@link BulkWriter} indexes by {@code log_id} (0.8), the
 * same id a re-run of the same dataset always recomputes.
 */
public final class IngestRunner {

    private IngestRunner() {
    }

    public static IngestReport run(OpenSearchClient client, IndexNames indexNames, DatasetManifest manifest,
                                    Path datasetDir, EventAssembler assembler, CatalogIndex catalogIndex,
                                    Matcher matcher, StackFrameResolver frameResolver,
                                    boolean recreateDataset, int batchSize) throws IOException {
        IndexManager indexManager = new IndexManager(client, indexNames);
        indexManager.ensureAll();
        if (recreateDataset) {
            indexManager.deleteByQuery(indexNames.logs(), Map.of("dataset_id", manifest.datasetId()));
        }

        DocumentReader reader = new DocumentReader(client);
        Map<String, CatalogEntry> catalogCache = new HashMap<>();

        long total = 0;
        long withException = 0;
        long withTraceId = 0;
        Map<String, Long> byStatus = new TreeMap<>();
        Map<String, Long> byConfidenceLevel = new TreeMap<>();
        Map<String, Long> byService = new TreeMap<>();
        Map<String, Long> byLevel = new TreeMap<>();

        Instant startedAt = Instant.now();
        try (BulkWriter<EnrichedLog> writer =
                 new BulkWriter<>(client, indexNames.logs(), EnrichedLog::logId, batchSize, BulkWriter.DEFAULT_MAX_BYTES)) {
            for (DatasetManifest.FileEntry fileEntry : manifest.files()) {
                Path logFile = datasetDir.resolve(fileEntry.path());
                if (!Files.isRegularFile(logFile)) {
                    throw new IngesterUserException("dataset log file not found: " + logFile.toAbsolutePath());
                }
                AssemblyContext ctx = new AssemblyContext(manifest.datasetId(), fileEntry.path(), fileEntry.service(),
                    fileEntry.module(), manifest.code(), manifest.logFormat(), manifest.oracle());

                try (Stream<LogEvent> events = assembler.assemble(logFile, ctx)) {
                    Iterator<LogEvent> iterator = events.iterator();
                    while (iterator.hasNext()) {
                        LogEvent event = iterator.next();
                        EnrichedLog enriched = EventEnricher.enrich(
                            event, catalogIndex, matcher, frameResolver, reader, indexNames, catalogCache);
                        writer.add(enriched);

                        total++;
                        byStatus.merge(enriched.match().status(), 1L, Long::sum);
                        if (enriched.match().confidenceLevel() != null) {
                            byConfidenceLevel.merge(enriched.match().confidenceLevel(), 1L, Long::sum);
                        }
                        byService.merge(enriched.service(), 1L, Long::sum);
                        byLevel.merge(enriched.level().name(), 1L, Long::sum);
                        if (enriched.exception() != null) {
                            withException++;
                        }
                        if (enriched.traceId() != null) {
                            withTraceId++;
                        }
                    }
                }
            }
            writer.flush();
            BulkWriter.Report bulkReport = writer.report();
            if (bulkReport.failed() > 0) {
                throw new IOException("bulk write failed for " + bulkReport.failed()
                    + " document(s), first error(s): " + bulkReport.errors());
            }
        }
        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(startedAt, finishedAt).toMillis();
        double eventsPerSecond = durationMs == 0 ? total : total * 1000.0 / durationMs;

        return new IngestReport(manifest.datasetId(), total, Map.copyOf(byStatus), Map.copyOf(byConfidenceLevel),
            Map.copyOf(byService), Map.copyOf(byLevel), withException, withTraceId, durationMs, eventsPerSecond, finishedAt);
    }
}
