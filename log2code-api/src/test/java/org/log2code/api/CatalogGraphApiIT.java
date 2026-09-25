package org.log2code.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.CandidateDetailDto;
import org.log2code.api.dto.CatalogEntryDto;
import org.log2code.api.dto.MethodDetailDto;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.dto.SourceFileDto;
import org.log2code.api.dto.SourceLookupResponse;
import org.log2code.api.dto.TraceResponse;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.SourceFile;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T24 IT: catalog, sources, candidates, neighbors, trace and methods/callers against a real
 * OpenSearch, seeded directly (same pattern as {@link ApiIT}, 0.14: no {@code log2code-ingester}
 * or {@code log2code-analyzer} dependency). Covers AC1 (controller behavior end to end), AC2
 * (exact {@code scope=service} neighbors by {@code sequence}) and AC3 ({@code OwnerResource.java}
 * fixture round-trips whole, with a matching {@code line_count}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class CatalogGraphApiIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final IndexNames INDEX_NAMES = new IndexNames();
    private static final String DATASET_ID = "it-graph-01";
    private static final Instant BASE_TIME = Instant.parse("2026-09-24T10:00:00Z");
    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-it-graph", "v1");
    private static final Path OWNER_RESOURCE_FIXTURE = Path.of("..", "fixtures", "java", "sources", "OwnerResource.java");

    private static OpenSearchClient seedClient;
    private static String ownerFileId;
    private static String ownerFileContent;
    private static int ownerFileLineCount;

    private static String neighborsMidLogId;
    private static String threadScopeMidLogId;
    private static String traceLogId;
    private static String noTraceLogId;
    private static String candidatesLogId;

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

        seedSourceFile();
        seedCatalog();
        seedMethods();
        seedLogs();

        for (String index : List.of(INDEX_NAMES.sources(), INDEX_NAMES.catalog(), INDEX_NAMES.methods(), INDEX_NAMES.logs())) {
            indexManager.refresh(index);
        }
    }

    @AfterAll
    static void closeSeedClient() throws IOException {
        OpenSearchClientFactory.close(seedClient);
    }

    // ---- GET /api/catalog/{statementId} ----

    @Test
    void catalogReturnsEntryWithControlAndWithoutRegex() {
        ResponseEntity<String> raw = restTemplate.getForEntity("/api/catalog/stmt-owner", String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain("\"regex\"");

        CatalogEntryDto dto = restTemplate.getForObject("/api/catalog/stmt-owner", CatalogEntryDto.class);
        assertThat(dto.statementId()).isEqualTo("stmt-owner");
        assertThat(dto.template()).isEqualTo("Saving owner {}");
        assertThat(dto.level()).isEqualTo("INFO");
        assertThat(dto.control().conditions()).hasSize(1);
        assertThat(dto.control().conditions().get(0).kind()).isEqualTo("if");
    }

    @Test
    void catalogReturns404ForUnknownStatementId() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/catalog/does-not-exist", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
    }

    // ---- GET /api/sources/{fileId} and /lookup (AC3) ----

    @Test
    void sourcesReturnsWholeFileWithMatchingLineCount() {
        ResponseEntity<SourceFileDto> response = restTemplate.getForEntity("/api/sources/" + ownerFileId, SourceFileDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isNotBlank();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=31536000, immutable");

        SourceFileDto body = response.getBody();
        assertThat(body.fileId()).isEqualTo(ownerFileId);
        assertThat(body.content()).isEqualTo(ownerFileContent);
        assertThat(body.lineCount()).isEqualTo(ownerFileLineCount);
        assertThat(body.codeUnit().name()).isEqualTo(CODE_UNIT.name());
    }

    @Test
    void sourcesReturns404ForUnknownFileId() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/sources/does-not-exist", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sourcesLookupReturnsSameFileId() {
        SourceLookupResponse response = restTemplate.getForObject(
            "/api/sources/lookup?codeUnit=" + CODE_UNIT.name() + "&version=" + CODE_UNIT.version() + "&path=OwnerResource.java",
            SourceLookupResponse.class);
        assertThat(response.fileId()).isEqualTo(ownerFileId);
    }

    @Test
    void sourcesLookupReturns404WhenNoMatch() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/api/sources/lookup?codeUnit=" + CODE_UNIT.name() + "&version=" + CODE_UNIT.version() + "&path=Missing.java",
            String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- GET /api/logs/{logId}/candidates ----

    @Test
    void candidatesJoinsCatalogDataAndToleratesStaleStatementId() {
        CandidateDetailDto[] candidates = restTemplate.getForObject(
            "/api/logs/" + candidatesLogId + "/candidates", CandidateDetailDto[].class);
        assertThat(candidates).hasSize(2);

        CandidateDetailDto known = candidates[0];
        assertThat(known.statementId()).isEqualTo("stmt-owner");
        assertThat(known.classFqn()).isEqualTo("org.springframework.samples.petclinic.customers.web.OwnerResource");
        assertThat(known.methodName()).isEqualTo("updateOwner");
        assertThat(known.line()).isEqualTo(89);
        assertThat(known.template()).isEqualTo("Saving owner {}");

        CandidateDetailDto stale = candidates[1];
        assertThat(stale.statementId()).isEqualTo("stmt-stale");
        assertThat(stale.score()).isEqualTo(0.4);
        assertThat(stale.classFqn()).isNull();
        assertThat(stale.template()).isNull();
    }

    // ---- GET /api/logs/{logId}/neighbors (AC2) ----

    @Test
    void neighborsScopeServiceReturnsExactSequenceNeighbors() {
        NeighborsResponse response = restTemplate.getForObject(
            "/api/logs/" + neighborsMidLogId + "/neighbors?before=2&after=3&scope=service", NeighborsResponse.class);

        assertThat(response.current().message()).isEqualTo("tick 5");
        assertThat(response.before()).extracting(s -> s.message()).containsExactly("tick 3", "tick 4");
        assertThat(response.after()).extracting(s -> s.message()).containsExactly("tick 6", "tick 7", "tick 8");
    }

    @Test
    void neighborsDefaultScopeIsServiceAndDoesNotCrossFiles() {
        // threadScopeMidLogId's own file only holds it plus one later event (seq 1); scope=service
        // (the default) must not pull in the cross-file thread-mates from the other seeded file.
        NeighborsResponse response = restTemplate.getForObject(
            "/api/logs/" + threadScopeMidLogId + "/neighbors?before=5&after=5", NeighborsResponse.class);

        assertThat(response.before()).isEmpty();
        assertThat(response.after()).extracting(s -> s.message()).containsExactly("thread-tick 4");
    }

    @Test
    void neighborsScopeThreadCrossesFiles() {
        NeighborsResponse response = restTemplate.getForObject(
            "/api/logs/" + threadScopeMidLogId + "/neighbors?before=5&after=5&scope=thread", NeighborsResponse.class);

        assertThat(response.before()).extracting(s -> s.message()).containsExactly("thread-tick 1");
        assertThat(response.after()).extracting(s -> s.message()).containsExactly("thread-tick 3", "thread-tick 4");
    }

    @Test
    void neighborsReturns404ForUnknownLogId() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/logs/does-not-exist/neighbors", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- GET /api/logs/{logId}/trace ----

    @Test
    void traceReturnsAllEventsWithSameTraceIdAcrossServicesInTimeOrder() {
        TraceResponse response = restTemplate.getForObject("/api/logs/" + traceLogId + "/trace", TraceResponse.class);

        assertThat(response.reason()).isNull();
        assertThat(response.items()).extracting(s -> s.service()).containsExactly(
            "api-gateway", "customers-service", "visits-service");
    }

    @Test
    void traceReturnsEmptyWithReasonWhenNoTraceId() {
        TraceResponse response = restTemplate.getForObject("/api/logs/" + noTraceLogId + "/trace", TraceResponse.class);

        assertThat(response.items()).isEmpty();
        assertThat(response.reason()).isEqualTo(TraceResponse.REASON_NO_TRACE_ID);
    }

    // ---- GET /api/methods/{methodId} and /callers ----

    @Test
    void methodDetailReturnsCalledBy() {
        MethodDetailDto detail = restTemplate.getForObject("/api/methods/method-map", MethodDetailDto.class);

        assertThat(detail.methodId()).isEqualTo("method-map");
        assertThat(detail.calledBy()).extracting(c -> c.methodId())
            .containsExactlyInAnyOrder("method-update-owner", "method-create-owner");
    }

    @Test
    void methodDetailReturns404ForUnknownMethodId() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/methods/does-not-exist", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void callersJoinsEachCallersOwnCallerCountAndAnnotationsNotTheTargets() {
        ResponseEntity<org.log2code.api.dto.CallerDto[]> response = restTemplate.getForEntity(
            "/api/methods/method-map/callers", org.log2code.api.dto.CallerDto[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<org.log2code.api.dto.CallerDto> callers = List.of(response.getBody());
        assertThat(callers).hasSize(2);

        org.log2code.api.dto.CallerDto updateOwner = callers.stream()
            .filter(c -> c.methodId().equals("method-update-owner")).findFirst().orElseThrow();
        assertThat(updateOwner.callerCount()).isEqualTo(0);
        assertThat(updateOwner.annotations()).containsExactly("PutMapping");

        org.log2code.api.dto.CallerDto createOwner = callers.stream()
            .filter(c -> c.methodId().equals("method-create-owner")).findFirst().orElseThrow();
        assertThat(createOwner.callerCount()).isEqualTo(3);
        assertThat(createOwner.annotations()).containsExactly("PostMapping");
    }

    @Test
    void callersReturns404ForUnknownMethodId() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/methods/does-not-exist/callers", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- seeding ----

    private static void seedSourceFile() throws IOException {
        byte[] bytes = Files.readAllBytes(OWNER_RESOURCE_FIXTURE);
        ownerFileContent = new String(bytes, StandardCharsets.UTF_8);
        ownerFileLineCount = (int) ownerFileContent.lines().count();
        ownerFileId = StableIds.fileId(CODE_UNIT.name(), CODE_UNIT.version(), "OwnerResource.java");
        String sha256 = sha256Hex(bytes);

        SourceFile source = new SourceFile(ownerFileId, CODE_UNIT, "spring-petclinic-customers-service",
            "OwnerResource.java", ownerFileContent, ownerFileLineCount, sha256);
        seedClient.index(i -> i.index(INDEX_NAMES.sources()).id(ownerFileId).document(source));
    }

    private static void seedCatalog() throws IOException {
        ControlContext control = new ControlContext(
            List.of(new Condition("if", "ownerId > 0", 85, false)), List.of(), List.of(), List.of());
        CatalogEntry entry = new CatalogEntry(
            "stmt-owner", "logical-owner", CODE_UNIT, "spring-petclinic-customers-service", "customers-service",
            "OwnerResource.java", ownerFileId, "customers.web",
            "org.springframework.samples.petclinic.customers.web.OwnerResource", "OwnerResource", "updateOwner",
            "updateOwner(int,OwnerRequest)", "method-update-owner", false, 89, 89, 8, 83, 91, "slf4j", "typed",
            "log", "OwnerResource", "class_literal", Level.INFO, false, "\"Saving owner {}\"", "Saving owner {}",
            "placeholders", null, "^Saving owner (.*)$", List.of("Saving", "owner"), 12, 1, false,
            new EnclosingBlock("method", null, null, 83, 91), control, "log.info(\"Saving owner {}\", ownerModel);",
            86, "https://github.com/spring-petclinic/spring-petclinic-microservices/blob/v1/OwnerResource.java#L89",
            "0.1.0-SNAPSHOT", BASE_TIME);
        seedClient.index(i -> i.index(INDEX_NAMES.catalog()).id(entry.statementId()).document(entry));
    }

    private static void seedMethods() throws IOException {
        MethodInfo mapMethod = new MethodInfo("method-map", CODE_UNIT, "spring-petclinic-customers-service",
            "customers-service", "file-mapper", "OwnerEntityMapper.java", "OwnerEntityMapper", "OwnerEntityMapper",
            "map", "map(Owner,OwnerRequest)", 20, 30, List.of(), false, List.of(),
            List.of(
                new CallerRef("method-update-owner", "OwnerResource", "updateOwner", ownerFileId, 88),
                new CallerRef("method-create-owner", "OwnerResource", "createOwner", ownerFileId, 60)),
            2);
        MethodInfo updateOwnerMethod = new MethodInfo("method-update-owner", CODE_UNIT,
            "spring-petclinic-customers-service", "customers-service", ownerFileId, "OwnerResource.java",
            "org.springframework.samples.petclinic.customers.web.OwnerResource", "OwnerResource", "updateOwner",
            "updateOwner(int,OwnerRequest)", 83, 91, List.of("PutMapping"), true, List.of(), List.of(), 0);
        MethodInfo createOwnerMethod = new MethodInfo("method-create-owner", CODE_UNIT,
            "spring-petclinic-customers-service", "customers-service", ownerFileId, "OwnerResource.java",
            "org.springframework.samples.petclinic.customers.web.OwnerResource", "OwnerResource", "createOwner",
            "createOwner(OwnerRequest)", 57, 62, List.of("PostMapping"), false, List.of(), List.of(), 3);

        try (BulkWriter<MethodInfo> writer = new BulkWriter<>(seedClient, INDEX_NAMES.methods(), MethodInfo::methodId)) {
            writer.add(mapMethod);
            writer.add(updateOwnerMethod);
            writer.add(createOwnerMethod);
            writer.flush();
        }
    }

    private static void seedLogs() throws IOException {
        try (BulkWriter<EnrichedLog> writer = new BulkWriter<>(seedClient, INDEX_NAMES.logs(), EnrichedLog::logId)) {
            // Neighbors series (AC2): 10 events in one file, sequence 0..9.
            String neighborsFile = "logs/customers-service.log";
            for (int i = 0; i < 10; i++) {
                EnrichedLog log = tickEvent(neighborsFile, i, i, "customers-service", "exec-1",
                    BASE_TIME.plusSeconds(i), "tick " + i, null, unmatched());
                if (i == 5) {
                    neighborsMidLogId = log.logId();
                }
                if (i == 0) {
                    noTraceLogId = log.logId();
                }
                writer.add(log);
            }

            // Thread-scope series: same thread, split across two files, interleaved in time.
            String fileA = "logs/customers-service-a.log";
            String fileB = "logs/customers-service-b.log";
            EnrichedLog t1 = tickEvent(fileA, 0, 0, "customers-service", "exec-9", BASE_TIME.plusSeconds(100), "thread-tick 1", null, unmatched());
            EnrichedLog t2 = tickEvent(fileB, 1, 0, "customers-service", "exec-9", BASE_TIME.plusSeconds(101), "thread-tick 2", null, unmatched());
            EnrichedLog t3 = tickEvent(fileA, 2, 1, "customers-service", "exec-9", BASE_TIME.plusSeconds(102), "thread-tick 3", null, unmatched());
            EnrichedLog t4 = tickEvent(fileB, 3, 1, "customers-service", "exec-9", BASE_TIME.plusSeconds(103), "thread-tick 4", null, unmatched());
            threadScopeMidLogId = t2.logId();
            writer.add(t1);
            writer.add(t2);
            writer.add(t3);
            writer.add(t4);

            // Trace trio: same trace_id, three services. Distinct source files from the neighbors
            // series above (which already uses "logs/customers-service.log" lineNumbers 0..9) so
            // StableIds.logId (keyed on dataset_id + source_file + line_number) cannot collide.
            String traceId = "trace-xyz";
            EnrichedLog g1 = tickEvent("logs/api-gateway.log", 0, 0, "api-gateway", "reactor-1", BASE_TIME.plusSeconds(200), "gateway hop", traceId, unmatched());
            EnrichedLog g2 = tickEvent("logs/customers-service-trace.log", 0, 0, "customers-service", "exec-2", BASE_TIME.plusSeconds(201), "customers hop", traceId, unmatched());
            EnrichedLog g3 = tickEvent("logs/visits-service.log", 0, 0, "visits-service", "exec-3", BASE_TIME.plusSeconds(202), "visits hop", traceId, unmatched());
            traceLogId = g1.logId();
            writer.add(g1);
            writer.add(g2);
            writer.add(g3);

            // Candidates: ambiguous match with a live and a stale (no longer cataloged) candidate.
            MatchResult ambiguous = new MatchResult(MatchResult.STATUS_AMBIGUOUS, "stmt-owner", 0.432, MatchResult.CONFIDENCE_LOW,
                List.of(new Candidate("stmt-owner", 0.72), new Candidate("stmt-stale", 0.4)), Map.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null);
            EnrichedLog c1 = tickEvent("logs/customers-service-candidates.log", 7, 0, "customers-service", "exec-4",
                BASE_TIME.plusSeconds(300), "ambiguous tick", null, ambiguous);
            candidatesLogId = c1.logId();
            writer.add(c1);

            writer.flush();
        }
    }

    private static EnrichedLog tickEvent(String sourceFile, int lineNumber, long sequence, String service,
            String thread, Instant timestamp, String message, String traceId, MatchResult match) {
        String logId = StableIds.logId(DATASET_ID, sourceFile, lineNumber);
        return new EnrichedLog(logId, timestamp, timestamp.toString(), DATASET_ID, sourceFile, lineNumber, 1,
            sequence, service, "spring-petclinic-" + service, "app", "1", thread, Level.INFO,
            "o.s.s.p." + service, null, message, message, traceId, traceId == null ? null : "span-" + sequence,
            null, new CodeVersion(CODE_UNIT.name(), CODE_UNIT.version()), match, null, "spring-boot-default",
            "test-seed", timestamp);
    }

    private static MatchResult unmatched() {
        return new MatchResult(MatchResult.STATUS_UNMATCHED, null, null, null, List.of(), Map.of(), List.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
