package org.log2code.ingester.match;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.MatchResult;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.ingester.assemble.AssemblyContext;
import org.log2code.ingester.assemble.EventAssembler;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.parse.LineParser;
import org.log2code.ingester.parse.LogFormatRegistry;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * T20 AC2 one-off manual measurement against the REAL running infra (localhost:9200, the actual
 * PetClinic catalog from T07-T15) and the real {@code smoke-oracle-01} dataset on disk - same pattern as
 * T19's {@code ManualCatalogIndexLoadCheck} (ADR-020), for the same reason: this needs the already
 * analyzed, real catalog and a real recorded oracle dataset, neither of which a Testcontainers-backed
 * {@code -Pit} test can reasonably reproduce (re-running the whole analyzer pipeline as test setup would
 * be slow and environment-dependent). Run once by hand; the printed lines are recorded in progress.md
 * (AC2: "bez praga; prava evaluacija je u T33").
 *
 * <p>The ground truth's {@code class} (0.9's {@code %C}) is the JVM's binary class name, which uses
 * {@code $} for a nested class (e.g. {@code Outer$Inner}) - the same notation as {@code CatalogEntry
 * .classBinary()}, not {@code .classFqn()} (0.7: {@code a.b.Outer.Inner} vs. {@code a.b.Outer$Inner}).
 * Comparing against {@code classFqn} alone under-counts correct predictions for every nested-class
 * statement (found empirically while investigating a lower-than-expected first measurement: it moved this
 * dataset's accuracy from 58.1% to 67.4% - see ADR-021's addendum).
 */
@Disabled("manual AC2 measurement against live infra; see docs/progress.md T20 entry for the recorded output")
class ManualSmokeOracleMatchAccuracyCheck {

    private static final String PROJECT_NAME = "spring-petclinic-microservices";
    private static final String PROJECT_VERSION = "3858f9c630cf989bb6809a86edf47c2be78dc9f1";
    private static final CodeVersion CODE = new CodeVersion(PROJECT_NAME, PROJECT_VERSION);
    private static final String DATASET_ID = "smoke-oracle-01";

    /** A known logging-wrapper class not (yet) in {@code oracle.unreliable-callers} (see the class javadoc). */
    private static final String LOG_LEVEL_WRAPPER = "org.springframework.boot.logging.LogLevel";

    private record DatasetFile(String path, String service, String module) {
    }

    // datasets/smoke-oracle-01/manifest.yml, transcribed (0.11's own files[] entries).
    private static final List<DatasetFile> FILES = List.of(
        new DatasetFile("logs/config-server.log.gz", "config-server", "spring-petclinic-config-server"),
        new DatasetFile("logs/discovery-server.log.gz", "discovery-server", "spring-petclinic-discovery-server"),
        new DatasetFile("logs/customers-service.log.gz", "customers-service", "spring-petclinic-customers-service"),
        new DatasetFile("logs/visits-service.log.gz", "visits-service", "spring-petclinic-visits-service"),
        new DatasetFile("logs/vets-service.log.gz", "vets-service", "spring-petclinic-vets-service"),
        new DatasetFile("logs/api-gateway.log.gz", "api-gateway", "spring-petclinic-api-gateway"));

    @Test
    void measureTop1AccuracyOverReliableGroundTruth() throws IOException {
        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of("http://localhost:9200"));
        try {
            CatalogIndex index = CatalogIndex.load(client, PROJECT_NAME, PROJECT_VERSION);
            MatchingConfig config = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));
            Matcher matcher = new Matcher(index, config);
            DocumentReader reader = new DocumentReader(client);
            IndexNames indexNames = new IndexNames();
            Map<String, CatalogEntry> entryCache = new HashMap<>();

            LineParser lineParser = LogFormatRegistry.load(Path.of("..", "config", "log-formats.yml"))
                .get("spring-boot-default");
            EventAssembler assembler = new EventAssembler(lineParser);

            Tally tally = new Tally();

            for (DatasetFile file : FILES) {
                AssemblyContext context = new AssemblyContext(DATASET_ID, file.path(), file.service(), file.module(), CODE,
                    "spring-boot-default", true);
                Path logFile = Path.of("..", "datasets", DATASET_ID, file.path());
                try (Stream<LogEvent> events = assembler.assemble(logFile, context)) {
                    for (LogEvent event : (Iterable<LogEvent>) events::iterator) {
                        GroundTruth gt = event.groundTruth();
                        if (gt == null || !Boolean.TRUE.equals(gt.reliable())) {
                            continue;
                        }
                        tally.reliableTotal++;
                        if (LOG_LEVEL_WRAPPER.equals(gt.className())) {
                            tally.logLevelWrapperGroundTruth++;
                        }
                        score(matcher.match(event), gt, reader, indexNames, entryCache, tally);
                    }
                }
            }

            tally.print();
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private static void score(MatchResult result, GroundTruth gt, DocumentReader reader, IndexNames indexNames,
                               Map<String, CatalogEntry> entryCache, Tally tally) {
        boolean isAmbiguous = MatchResult.STATUS_AMBIGUOUS.equals(result.status());
        boolean isMatched = MatchResult.STATUS_MATCHED.equals(result.status());
        if (isMatched) {
            tally.matchedCount++;
        }
        if (isAmbiguous) {
            tally.ambiguousCount++;
        }
        if (result.statementId() == null) {
            tally.unmatchedCount++;
            return;
        }
        tally.predicted++;
        CatalogEntry entry = entryCache.computeIfAbsent(result.statementId(), id -> {
            try {
                return reader.get(indexNames.catalog(), id, CatalogEntry.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (entry == null) {
            return;
        }
        // The ground truth's class name (0.9's %C, the JVM's caller-data) matches classBinary's
        // notation, not classFqn's, for a nested class - accept either (see the class javadoc).
        boolean classMatches = gt.className().equals(entry.classFqn()) || gt.className().equals(entry.classBinary());
        boolean classAndMethodMatch = classMatches && entry.methodName().equals(gt.method());
        if (classAndMethodMatch) {
            tally.correctClassAndMethod++;
            if (entry.line() == gt.line()) {
                tally.correct++;
                if (isMatched) {
                    tally.matchedCorrect++;
                }
                if (isAmbiguous) {
                    tally.ambiguousCorrect++;
                }
            }
        }
    }

    private static final class Tally {
        int reliableTotal;
        int predicted;
        int correct;
        int correctClassAndMethod;
        int matchedCount;
        int ambiguousCount;
        int unmatchedCount;
        int matchedCorrect;
        int ambiguousCorrect;
        int logLevelWrapperGroundTruth;

        void print() {
            System.out.printf(
                "T20 AC2: smoke-oracle-01, reliable ground truth = %d, predicted (matched/ambiguous) = %d "
                    + "(%.1f%%), top-1 correct (class+method+line) = %d (%.1f%% of reliable), "
                    + "class+method only = %d (%.1f%% of reliable)%n",
                reliableTotal, predicted, pct(predicted, reliableTotal),
                correct, pct(correct, reliableTotal),
                correctClassAndMethod, pct(correctClassAndMethod, reliableTotal));
            System.out.printf(
                "T20 AC2 breakdown: matched=%d (correct=%d, %.1f%%), ambiguous=%d (correct=%d, %.1f%%), "
                    + "unmatched=%d; reliable ground truth pointing at a known logging-wrapper method not "
                    + "(yet) in oracle.unreliable-callers (%s) = %d (%.1f%% of reliable)%n",
                matchedCount, matchedCorrect, pct(matchedCorrect, matchedCount),
                ambiguousCount, ambiguousCorrect, pct(ambiguousCorrect, ambiguousCount),
                unmatchedCount, LOG_LEVEL_WRAPPER, logLevelWrapperGroundTruth, pct(logLevelWrapperGroundTruth, reliableTotal));
        }

        private static double pct(int part, int whole) {
            return whole == 0 ? 0.0 : 100.0 * part / whole;
        }
    }
}
