package org.log2code.ingester.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.MatchResult;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.ingester.IngesterVersion;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.catalog.LoggerResolver.Resolution;
import org.log2code.ingester.enrich.StackFrameResolver;
import org.log2code.ingester.match.Matcher;

/**
 * Turns one {@link LogEvent} into an {@link EnrichedLog} (T21's "obogaćivanje" step: match against
 * the catalog, denormalize the winning statement's fields, resolve the exception's stack frames,
 * resolve {@code logger}) - extracted out of {@link IngestRunner} (ADR-023) so T22's follow mode
 * reuses the exact same enrichment pipeline instead of a second implementation that could drift from
 * batch ingest's.
 */
public final class EventEnricher {

    private EventEnricher() {
    }

    public static EnrichedLog enrich(LogEvent event, CatalogIndex catalogIndex, Matcher matcher,
                                      StackFrameResolver frameResolver, DocumentReader reader, IndexNames indexNames,
                                      Map<String, CatalogEntry> catalogCache) throws IOException {
        MatchResult match = matcher.match(event);
        if (match.statementId() != null) {
            CatalogEntry winner = catalogCache.computeIfAbsent(match.statementId(), id -> get(reader, indexNames.catalog(), id));
            if (winner != null) {
                match = match.withDenormalizedFrom(winner);
            }
        }
        ExceptionInfo exception = frameResolver.resolve(event.exception(), event.service());
        String logger = resolveLogger(catalogIndex, event);

        return new EnrichedLog(
            StableIds.logId(event.datasetId(), event.sourceFile(), event.lineNumber()),
            event.timestamp(),
            event.timestampRaw(),
            event.datasetId(),
            event.sourceFile(),
            event.lineNumber(),
            event.lineCount(),
            event.sequence(),
            event.service(),
            event.module(),
            event.appName(),
            event.pid(),
            event.thread(),
            event.level(),
            event.loggerRaw(),
            logger,
            event.message(),
            event.raw(),
            event.traceId(),
            event.spanId(),
            exception,
            event.code(),
            match,
            event.groundTruth(),
            event.parserFormat(),
            IngesterVersion.current(),
            Instant.now());
    }

    /**
     * {@code logger} (0.7: "razrešen FQN ili null"): the single unambiguous name 0.10 step 1's
     * resolution names (T19's {@code LoggerResolver}, cached there) - {@code null} when unresolved or
     * still ambiguous (more than one plausible name), same as an {@code abbrev_multi} candidate pool.
     */
    private static String resolveLogger(CatalogIndex catalogIndex, LogEvent event) {
        Resolution resolution = catalogIndex.loggerResolver().resolve(event.loggerRaw(), event.service());
        return resolution.names().size() == 1 ? resolution.names().iterator().next() : null;
    }

    private static CatalogEntry get(DocumentReader reader, String index, String id) {
        try {
            return reader.get(index, id, CatalogEntry.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
