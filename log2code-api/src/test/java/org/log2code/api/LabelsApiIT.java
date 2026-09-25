package org.log2code.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.LabelDto;
import org.log2code.api.dto.LabelSearchResponse;
import org.log2code.api.dto.LogSummary;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Label;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T25 IT: {@code /api/labels/**} and {@code /api/review-queue} against a real OpenSearch, seeded
 * directly (same pattern as {@link CatalogGraphApiIT}, 0.14). Covers AC2 (label CRUD) and AC3
 * (review-queue excludes already-labeled logs).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class LabelsApiIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final IndexNames INDEX_NAMES = new IndexNames();
    private static final String DATASET_ID = "it-labels-01";
    private static final Instant BASE_TIME = Instant.parse("2026-09-25T10:00:00Z");

    private static OpenSearchClient seedClient;
    private static String plainLogId;

    private static String ambiguousLogId;
    private static String lowConfidenceLogId;
    private static String unreliableGroundTruthLogId;
    private static String confidentLogId;
    private static String alreadyLabeledLogId;

    @DynamicPropertySource
    static void openSearchProperties(DynamicPropertyRegistry registry) {
        registry.add("log2code.opensearch.url", OPENSEARCH::getHttpHostAddress);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void seed() throws IOException {
        seedClient = OpenSearchClientFactory.create(OpenSearchConfig.of(OPENSEARCH.getHttpHostAddress()));
        IndexManager indexManager = new IndexManager(seedClient, INDEX_NAMES);
        indexManager.ensureAll();
        seedLogs();
        indexManager.refresh(INDEX_NAMES.logs());
    }

    // ---- PUT/GET/DELETE /api/labels/{logId} (AC2) ----

    @Test
    void putThenGetRoundTripsAndFillsDatasetAndPredictedStatementIdFromTheLog() {
        ResponseEntity<LabelDto> putResponse = restTemplate.exchange("/api/labels/" + plainLogId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("verdict", "correct", "note", "looks right")), LabelDto.class);
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        LabelDto put = putResponse.getBody();
        assertThat(put.logId()).isEqualTo(plainLogId);
        assertThat(put.datasetId()).isEqualTo(DATASET_ID);
        assertThat(put.verdict()).isEqualTo("correct");
        assertThat(put.predictedStatementId()).isEqualTo("stmt-plain");
        assertThat(put.note()).isEqualTo("looks right");

        LabelDto fetched = restTemplate.getForObject("/api/labels/" + plainLogId, LabelDto.class);
        assertThat(fetched.verdict()).isEqualTo("correct");
        assertThat(fetched.predictedStatementId()).isEqualTo("stmt-plain");

        restTemplate.delete("/api/labels/" + plainLogId);
        ResponseEntity<String> afterDelete = restTemplate.getForEntity("/api/labels/" + plainLogId, String.class);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void putReturns404WhenLogDoesNotExist() {
        ResponseEntity<String> response = restTemplate.exchange("/api/labels/does-not-exist", HttpMethod.PUT,
            new HttpEntity<>(Map.of("verdict", "correct")), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void putReturns400ForAnUnknownVerdict() {
        ResponseEntity<String> response = restTemplate.exchange("/api/labels/" + plainLogId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("verdict", "maybe")), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getReturns404WhenNoLabelExists() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/labels/" + confidentLogId, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listFiltersByDatasetAndVerdict() {
        restTemplate.exchange("/api/labels/" + plainLogId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("verdict", "incorrect", "correctStatementId", "stmt-real")), LabelDto.class);

        LabelSearchResponse response = restTemplate.getForObject(
            "/api/labels?datasetId=" + DATASET_ID + "&verdict=incorrect", LabelSearchResponse.class);
        assertThat(response.items()).extracting(l -> l.logId()).contains(plainLogId);
        assertThat(response.items()).allMatch(l -> l.verdict().equals("incorrect"));

        restTemplate.delete("/api/labels/" + plainLogId);
    }

    // ---- GET /api/review-queue (AC3) ----

    @Test
    void reviewQueueOnlyReturnsEligibleUnlabeledLogs() {
        // alreadyLabeledLogId is ambiguous (eligible) but already labeled — AC3: must not come back.
        restTemplate.exchange("/api/labels/" + alreadyLabeledLogId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("verdict", "correct")), LabelDto.class);

        LogSummary[] queue = restTemplate.getForObject(
            "/api/review-queue?datasetId=" + DATASET_ID + "&limit=50&seed=1", LogSummary[].class);
        List<String> ids = List.of(queue).stream().map(LogSummary::logId).toList();

        assertThat(ids).contains(ambiguousLogId, lowConfidenceLogId, unreliableGroundTruthLogId);
        assertThat(ids).doesNotContain(confidentLogId, alreadyLabeledLogId);

        restTemplate.delete("/api/labels/" + alreadyLabeledLogId);
    }

    @Test
    void reviewQueueOrderIsReproducibleForTheSameSeed() {
        LogSummary[] first = restTemplate.getForObject(
            "/api/review-queue?datasetId=" + DATASET_ID + "&limit=50&seed=7", LogSummary[].class);
        LogSummary[] second = restTemplate.getForObject(
            "/api/review-queue?datasetId=" + DATASET_ID + "&limit=50&seed=7", LogSummary[].class);

        assertThat(List.of(first).stream().map(LogSummary::logId).toList())
            .containsExactlyElementsOf(List.of(second).stream().map(LogSummary::logId).toList());
    }

    @Test
    void reviewQueueReturns400ForNonPositiveLimit() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/api/review-queue?datasetId=" + DATASET_ID + "&limit=0", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- seeding ----

    private static void seedLogs() throws IOException {
        try (BulkWriter<EnrichedLog> writer = new BulkWriter<>(seedClient, INDEX_NAMES.logs(), EnrichedLog::logId)) {
            String file = "logs/customers-service.log";

            EnrichedLog plain = event(file, 0, matched("stmt-plain", MatchResult.CONFIDENCE_HIGH), null);
            plainLogId = plain.logId();
            writer.add(plain);

            EnrichedLog ambiguous = event(file, 1,
                new MatchResult(MatchResult.STATUS_AMBIGUOUS, "stmt-a", 0.4, MatchResult.CONFIDENCE_LOW,
                    List.of(), Map.of(), List.of(), null, null, null, null, null, null, null, null, null, null), null);
            ambiguousLogId = ambiguous.logId();
            writer.add(ambiguous);

            EnrichedLog lowConfidence = event(file, 2, matched("stmt-b", MatchResult.CONFIDENCE_LOW), null);
            lowConfidenceLogId = lowConfidence.logId();
            writer.add(lowConfidence);

            EnrichedLog unreliable = event(file, 3, matched("stmt-c", MatchResult.CONFIDENCE_HIGH),
                new GroundTruth("a.Foo", "bar", 3, false));
            unreliableGroundTruthLogId = unreliable.logId();
            writer.add(unreliable);

            EnrichedLog confident = event(file, 4, matched("stmt-d", MatchResult.CONFIDENCE_HIGH),
                new GroundTruth("a.Foo", "bar", 4, true));
            confidentLogId = confident.logId();
            writer.add(confident);

            EnrichedLog alreadyLabeled = event(file, 5,
                new MatchResult(MatchResult.STATUS_AMBIGUOUS, "stmt-e", 0.4, MatchResult.CONFIDENCE_LOW,
                    List.of(), Map.of(), List.of(), null, null, null, null, null, null, null, null, null, null), null);
            alreadyLabeledLogId = alreadyLabeled.logId();
            writer.add(alreadyLabeled);

            writer.flush();
        }
    }

    private static MatchResult matched(String statementId, String confidenceLevel) {
        return new MatchResult(MatchResult.STATUS_MATCHED, statementId, 0.9, confidenceLevel, List.of(), Map.of(),
            List.of(), null, null, null, null, null, null, null, null, null, null);
    }

    private static EnrichedLog event(String sourceFile, int lineNumber, MatchResult match, GroundTruth groundTruth) {
        String logId = StableIds.logId(DATASET_ID, sourceFile, lineNumber);
        Instant timestamp = BASE_TIME.plusSeconds(lineNumber);
        return new EnrichedLog(logId, timestamp, timestamp.toString(), DATASET_ID, sourceFile, lineNumber, 1,
            lineNumber, "customers-service", "spring-petclinic-customers-service", "app", "1", "thread-1", Level.INFO,
            "a.Foo", "a.Foo", "tick " + lineNumber, "tick " + lineNumber, null, null, null,
            new CodeVersion("petclinic", "v1"), match, groundTruth, "spring-boot-default", "test-seed", timestamp);
    }
}
