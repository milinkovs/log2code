package org.log2code.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.CodeUnitSummary;
import org.log2code.api.dto.DatasetSummary;
import org.log2code.api.dto.LogDetail;
import org.log2code.api.dto.LogSearchResponse;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.ModuleInfo;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T23 IT: the whole {@code log2code-api} app against a real OpenSearch, seeded directly (no
 * ingester dependency, 0.14: api does not depend on the ingester module) with 25 hand-built
 * {@link EnrichedLog} documents. Covers AC2 (pagination across 3 pages, no overlap, stable order)
 * and AC3 (service/level/status filters), plus {@code /api/logs/{logId}} and the meta endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ApiIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final IndexNames INDEX_NAMES = new IndexNames();
    private static final String DATASET_ID = "it-logs-01";
    private static final Instant BASE_TIME = Instant.parse("2026-09-24T10:00:00Z");
    private static final int CUSTOMERS_COUNT = 20;
    private static final int VISITS_COUNT = 5;

    private static OpenSearchClient seedClient;

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

        try (BulkWriter<EnrichedLog> writer = new BulkWriter<>(seedClient, INDEX_NAMES.logs(), EnrichedLog::logId)) {
            for (int i = 0; i < CUSTOMERS_COUNT; i++) {
                writer.add(logEvent(i, "customers-service", Level.INFO, "Saving owner " + i,
                    matched("stmt-owner"), false));
            }
            for (int i = 0; i < VISITS_COUNT; i++) {
                writer.add(logEvent(CUSTOMERS_COUNT + i, "visits-service", Level.ERROR, "boom " + i,
                    unmatched(), true));
            }
            writer.flush();
        }

        AnalysisRun run = new AnalysisRun("run-it-1", CodeUnit.TYPE_PROJECT,
            new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-it", "v1"), "https://example.invalid/petclinic-it",
            "test-analyzer", BASE_TIME, BASE_TIME.plusSeconds(5), 5000, Map.of(),
            List.of(new ModuleInfo("spring-petclinic-customers-service", "customers-service",
                List.of("src/main/java"), List.of(), List.of())));
        seedClient.index(i -> i.index(INDEX_NAMES.runs()).id(run.runId()).document(run));

        indexManager.refresh(INDEX_NAMES.logs());
        indexManager.refresh(INDEX_NAMES.runs());
    }

    @AfterAll
    static void closeSeedClient() throws IOException {
        OpenSearchClientFactory.close(seedClient);
    }

    @Test
    void searchPaginatesAcrossThreePagesWithoutOverlapInStableOrder() {
        List<String> seenIds = new ArrayList<>();
        String searchAfter = null;
        int totalFetched = 0;

        for (int page = 0; page < 3; page++) {
            // TestRestTemplate already percent-encodes the query string it's given; pre-encoding
            // here would double-encode the cursor and the server would reject it as invalid.
            String url = "/api/logs?datasetId=" + DATASET_ID + "&size=10&order=asc"
                + (searchAfter == null ? "" : "&searchAfter=" + searchAfter);
            LogSearchResponse response = restTemplate.getForObject(url, LogSearchResponse.class);
            assertThat(response).isNotNull();
            assertThat(response.total()).isEqualTo(CUSTOMERS_COUNT + VISITS_COUNT);

            seenIds.addAll(response.items().stream().map(item -> item.logId()).toList());
            totalFetched += response.items().size();
            searchAfter = response.nextSearchAfter();

            if (page < 2) {
                assertThat(response.items()).hasSize(10);
            } else {
                assertThat(response.items()).hasSize(5);
            }
        }

        assertThat(totalFetched).isEqualTo(25);
        assertThat(seenIds).doesNotHaveDuplicates().hasSize(25);
    }

    @Test
    void searchFiltersByServiceLevelAndStatus() {
        assertThat(searchTotal("service=customers-service")).isEqualTo(CUSTOMERS_COUNT);
        assertThat(searchTotal("level=ERROR")).isEqualTo(VISITS_COUNT);
        assertThat(searchTotal("status=unmatched")).isEqualTo(VISITS_COUNT);
        assertThat(searchTotal("service=visits-service&level=ERROR&status=unmatched")).isEqualTo(VISITS_COUNT);
        assertThat(searchTotal("service=customers-service&level=ERROR")).isEqualTo(0);
    }

    @Test
    void detailReturnsFullDocumentAndUnknownIdIs404() {
        LogSearchResponse firstPage = restTemplate.getForObject(
            "/api/logs?datasetId=" + DATASET_ID + "&size=1&order=asc", LogSearchResponse.class);
        assertThat(firstPage.items()).hasSize(1);
        String logId = firstPage.items().get(0).logId();

        LogDetail detail = restTemplate.getForObject("/api/logs/" + logId, LogDetail.class);
        assertThat(detail.logId()).isEqualTo(logId);
        assertThat(detail.datasetId()).isEqualTo(DATASET_ID);
        assertThat(detail.match().statementId()).isEqualTo("stmt-owner");
        assertThat(detail.raw()).isNotBlank();

        ResponseEntity<String> notFound = restTemplate.getForEntity("/api/logs/does-not-exist", String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getHeaders().getContentType()).hasToString("application/problem+json");
    }

    @Test
    void metaEndpointsReturnDatasetsServicesAndCodeUnits() {
        ResponseEntity<List<DatasetSummary>> datasets = restTemplate.exchange("/api/meta/datasets", HttpMethod.GET,
            null, new ParameterizedTypeReference<>() {
            });
        assertThat(datasets.getBody()).extracting(DatasetSummary::datasetId).contains(DATASET_ID);
        assertThat(datasets.getBody()).filteredOn(d -> d.datasetId().equals(DATASET_ID))
            .extracting(DatasetSummary::count).containsExactly(25L);

        ResponseEntity<List<String>> services = restTemplate.exchange(
            "/api/meta/services?datasetId=" + DATASET_ID, HttpMethod.GET, null, new ParameterizedTypeReference<>() {
            });
        assertThat(services.getBody()).containsExactlyInAnyOrder("customers-service", "visits-service");

        ResponseEntity<List<CodeUnitSummary>> codeUnits = restTemplate.exchange("/api/meta/code-units",
            HttpMethod.GET, null, new ParameterizedTypeReference<>() {
            });
        assertThat(codeUnits.getBody()).extracting(CodeUnitSummary::name).contains("petclinic-it");
    }

    private long searchTotal(String query) {
        LogSearchResponse response = restTemplate.getForObject(
            "/api/logs?datasetId=" + DATASET_ID + "&size=1&" + query, LogSearchResponse.class);
        return response.total();
    }

    private static EnrichedLog logEvent(int sequence, String service, Level level, String message,
            MatchResult match, boolean withException) {
        String sourceFile = "logs/" + service + ".log";
        int lineNumber = sequence + 1;
        String logId = StableIds.logId(DATASET_ID, sourceFile, lineNumber);
        Instant timestamp = BASE_TIME.plusSeconds(sequence);
        return new EnrichedLog(logId, timestamp, timestamp.toString(), DATASET_ID, sourceFile, lineNumber, 1,
            sequence, service, "spring-petclinic-" + service, "app", "1", "thread-1", level,
            "o.s.s.p." + service, "org.springframework.samples.petclinic." + service, message, message,
            null, null, withException ? exceptionInfo() : null, new CodeVersion("petclinic-it", "v1"), match,
            null, "spring-boot-default", "test-seed", timestamp);
    }

    private static ExceptionInfo exceptionInfo() {
        return new ExceptionInfo("java.lang.RuntimeException", "java.lang.RuntimeException",
            "boom", List.of(), List.of());
    }

    private static MatchResult matched(String statementId) {
        return new MatchResult(MatchResult.STATUS_MATCHED, statementId, 0.9, MatchResult.CONFIDENCE_HIGH,
            List.of(), Map.of(), List.of(), "petclinic-it", "spring-petclinic-customers-service",
            "org.springframework.samples.petclinic.customers.web.OwnerResource", "updateOwner",
            "spring-petclinic-customers-service/src/main/java/.../OwnerResource.java", "slf4j", "placeholders",
            89, "Saving owner {}", null);
    }

    private static MatchResult unmatched() {
        return new MatchResult(MatchResult.STATUS_UNMATCHED, null, null, null, List.of(), Map.of(), List.of(),
            null, null, null, null, null, null, null, null, null, null);
    }
}
