package org.log2code.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.ContextBundleDto;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.StackFrame;
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
 * T25 IT: {@code GET /api/logs/{logId}/context} against a real OpenSearch, seeded directly (same
 * pattern as {@link CatalogGraphApiIT}, 0.14). Covers AC1: the bundle for a log with an exception
 * resolves the project frame with a ±3-line snippet, alongside the rest of the assembled sections
 * (statement, code, control, callers, neighbors, trace).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ContextApiIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final IndexNames INDEX_NAMES = new IndexNames();
    private static final String DATASET_ID = "it-context-01";
    private static final Instant BASE_TIME = Instant.parse("2026-09-25T10:00:00Z");
    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-it-context", "v1");
    private static final String OWNER_FILE_CONTENT = String.join("\n",
        "package a;",                              // 1
        "class OwnerResource {",                   // 2
        "  void updateOwner() {",                  // 3
        "    validate();",                         // 4
        "    log.info(\"Saving owner {}\", owner);", // 5
        "    save(owner);",                         // 6
        "  }",                                      // 7
        "}");                                       // 8
    private static final String MAPPER_FILE_CONTENT = String.join("\n",
        "package a;",             // 1
        "class OwnerMapper {",    // 2
        "  Owner map() {",        // 3
        "    return owner;",      // 4
        "  }",                    // 5
        "}");                     // 6

    private static OpenSearchClient seedClient;
    private static String ownerFileId;
    private static String mapperFileId;
    private static String withExceptionLogId;
    private static String matchedNoExceptionLogId;

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

        seedSources();
        seedCatalog();
        seedMethods();
        seedLogs();

        for (String index : List.of(INDEX_NAMES.sources(), INDEX_NAMES.catalog(), INDEX_NAMES.methods(), INDEX_NAMES.logs())) {
            indexManager.refresh(index);
        }
    }

    @Test
    void contextResolvesProjectExceptionFrameWithSnippetAndAssemblesEveryOtherSection() {
        ContextBundleDto bundle = restTemplate.getForObject(
            "/api/logs/" + withExceptionLogId + "/context?neighbors=1", ContextBundleDto.class);

        assertThat(bundle.schemaVersion()).isEqualTo(1);
        assertThat(bundle.log().logId()).isEqualTo(withExceptionLogId);

        // match (AC1 context)
        assertThat(bundle.match().status()).isEqualTo("matched");
        assertThat(bundle.match().candidates()).extracting(c -> c.statementId()).contains("stmt-owner");

        // statement + code
        assertThat(bundle.statement().statementId()).isEqualTo("stmt-owner");
        assertThat(bundle.statement().template()).isEqualTo("Saving owner {}");
        assertThat(bundle.code().filePath()).isEqualTo("OwnerResource.java");
        assertThat(bundle.code().methodSource()).isEqualTo(
            "  void updateOwner() {\n    validate();\n    log.info(\"Saving owner {}\", owner);\n    save(owner);\n  }");

        // control (T11, reused verbatim from the catalog entry)
        assertThat(bundle.control().conditions()).hasSize(1);
        assertThat(bundle.control().conditions().get(0).kind()).isEqualTo("if");

        // callers (one level, with a snippet around the call site)
        assertThat(bundle.callers()).hasSize(1);
        assertThat(bundle.callers().get(0).classFqn()).isEqualTo("a.OwnerMapper");
        assertThat(bundle.callers().get(0).filePath()).isEqualTo("OwnerMapper.java");
        assertThat(bundle.callers().get(0).snippet()).isEqualTo(MAPPER_FILE_CONTENT);

        // exception: AC1 — the project frame is resolved with a ±3-line snippet, the library frame is not
        assertThat(bundle.exception().className()).isEqualTo("java.lang.IllegalStateException");
        assertThat(bundle.exception().frames()).hasSize(2);
        var projectFrame = bundle.exception().frames().stream().filter(f -> f.inProject()).findFirst().orElseThrow();
        assertThat(projectFrame.snippet()).isEqualTo(
            "class OwnerResource {\n  void updateOwner() {\n    validate();\n    log.info(\"Saving owner {}\", owner);\n    save(owner);\n  }\n}");
        var libraryFrame = bundle.exception().frames().stream().filter(f -> !f.inProject()).findFirst().orElseThrow();
        assertThat(libraryFrame.snippet()).isNull();
        assertThat(bundle.exception().causedBy()).hasSize(1);
        assertThat(bundle.exception().causedBy().get(0).frames().get(0).snippet()).isNotNull();

        // neighbors: narrower {before, after} (no "current" — already at bundle.log())
        assertThat(bundle.neighbors().before()).extracting(s -> s.message()).containsExactly("Saving owner Owner[0]");
        assertThat(bundle.neighbors().after()).extracting(s -> s.message()).containsExactly("Saving owner Owner[2]");

        // trace: kept as {items, reason} (dogovoreno sa korisnikom)
        assertThat(bundle.trace().reason()).isEqualTo("no-trace-id");
        assertThat(bundle.trace().items()).isEmpty();
    }

    @Test
    void contextLeavesStatementCodeControlAndCallersNullAndExceptionNullWhenNoExceptionOrMatch() {
        ContextBundleDto bundle = restTemplate.getForObject(
            "/api/logs/" + matchedNoExceptionLogId + "/context", ContextBundleDto.class);

        assertThat(bundle.exception()).isNull();
        assertThat(bundle.match().status()).isEqualTo("matched");
    }

    @Test
    void contextReturns404ForUnknownLogId() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/logs/does-not-exist/context", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- seeding ----

    private static void seedSources() throws IOException {
        ownerFileId = "owner-file-1";
        mapperFileId = "mapper-file-1";
        seedClient.index(i -> i.index(INDEX_NAMES.sources()).id(ownerFileId)
            .document(new SourceFile(ownerFileId, CODE_UNIT, "spring-petclinic-customers-service", "OwnerResource.java",
                OWNER_FILE_CONTENT, 8, "sha-owner")));
        seedClient.index(i -> i.index(INDEX_NAMES.sources()).id(mapperFileId)
            .document(new SourceFile(mapperFileId, CODE_UNIT, "spring-petclinic-customers-service", "OwnerMapper.java",
                MAPPER_FILE_CONTENT, 6, "sha-mapper")));
    }

    private static void seedCatalog() throws IOException {
        ControlContext control = new ControlContext(List.of(new Condition("if", "owner != null", 4, false)), List.of(), List.of(), List.of());
        CatalogEntry entry = new CatalogEntry(
            "stmt-owner", "logical-owner", CODE_UNIT, "spring-petclinic-customers-service", "customers-service",
            "OwnerResource.java", ownerFileId, "a", "a.OwnerResource", "OwnerResource", "updateOwner", "updateOwner()",
            "method-update-owner", false, 5, 5, 4, 3, 7, "slf4j", "typed", "log", "OwnerResource", "class_literal",
            Level.INFO, false, "\"Saving owner {}\"", "Saving owner {}", "placeholders", null, "^Saving owner (.*)$",
            List.of("Saving", "owner"), 12, 1, false, new EnclosingBlock("method", null, null, 3, 7), control,
            "log.info(\"Saving owner {}\", owner);", 5,
            "https://github.com/spring-petclinic/spring-petclinic-microservices/blob/v1/OwnerResource.java#L5",
            "0.1.0-SNAPSHOT", BASE_TIME);
        seedClient.index(i -> i.index(INDEX_NAMES.catalog()).id(entry.statementId()).document(entry));
    }

    private static void seedMethods() throws IOException {
        MethodInfo updateOwnerMethod = new MethodInfo("method-update-owner", CODE_UNIT, "spring-petclinic-customers-service",
            "customers-service", ownerFileId, "OwnerResource.java", "a.OwnerResource", "OwnerResource", "updateOwner",
            "updateOwner()", 3, 7, List.of(), true, List.of(),
            List.of(new CallerRef("method-map", "a.OwnerMapper", "map", mapperFileId, 3)), 1);
        MethodInfo mapMethod = new MethodInfo("method-map", CODE_UNIT, "spring-petclinic-customers-service",
            "customers-service", mapperFileId, "OwnerMapper.java", "a.OwnerMapper", "OwnerMapper", "map", "map()",
            2, 5, List.of(), false, List.of(), List.of(), 0);

        try (BulkWriter<MethodInfo> writer = new BulkWriter<>(seedClient, INDEX_NAMES.methods(), MethodInfo::methodId)) {
            writer.add(updateOwnerMethod);
            writer.add(mapMethod);
            writer.flush();
        }
    }

    private static void seedLogs() throws IOException {
        try (BulkWriter<EnrichedLog> writer = new BulkWriter<>(seedClient, INDEX_NAMES.logs(), EnrichedLog::logId)) {
            MatchResult matched = new MatchResult(MatchResult.STATUS_MATCHED, "stmt-owner", 0.95, MatchResult.CONFIDENCE_HIGH,
                List.of(new Candidate("stmt-owner", 0.95)), Map.of(), List.of("Owner[1]"),
                "petclinic-it-context", "customers-service", "a.OwnerResource", "updateOwner", "OwnerResource.java",
                "slf4j", "placeholders", 5, "Saving owner {}", null);

            String file = "logs/customers-service.log";
            EnrichedLog neighbor = tickEvent(file, 0, 0, "Saving owner Owner[0]", null, matched);
            writer.add(neighbor);

            StackFrame projectFrame = new StackFrame("a.OwnerResource", "updateOwner", "OwnerResource.java", 5, true,
                CODE_UNIT.name() + ":" + CODE_UNIT.version(), ownerFileId,
                "https://github.com/spring-petclinic/spring-petclinic-microservices/blob/v1/OwnerResource.java#L5");
            StackFrame libraryFrame = new StackFrame("org.springframework.web.servlet.DispatcherServlet", "doDispatch",
                "DispatcherServlet.java", 1234, false, "org.springframework:spring-webmvc:6.2.0", null, null);
            ExceptionInfo exception = new ExceptionInfo("java.lang.IllegalStateException", "java.lang.IllegalStateException",
                "boom", List.of(projectFrame, libraryFrame), List.of(new CausedBy("java.lang.RuntimeException", "root cause", List.of(projectFrame))));
            EnrichedLog withException = new EnrichedLog(logId(1), BASE_TIME.plusSeconds(1), BASE_TIME.plusSeconds(1).toString(),
                DATASET_ID, file, 1, 1, 1, "customers-service", "spring-petclinic-customers-service", "app", "1",
                "thread-1", Level.ERROR, "a.OwnerResource", "a.OwnerResource", "boom", "boom\n\tat a.OwnerResource...",
                null, null, exception, new CodeVersion(CODE_UNIT.name(), CODE_UNIT.version()), matched, null,
                "spring-boot-default", "test-seed", BASE_TIME.plusSeconds(1));
            withExceptionLogId = withException.logId();
            writer.add(withException);

            EnrichedLog matchedNoException = tickEvent(file, 2, 2, "Saving owner Owner[2]", null, matched);
            matchedNoExceptionLogId = matchedNoException.logId();
            writer.add(matchedNoException);

            writer.flush();
        }
    }

    private static EnrichedLog tickEvent(String sourceFile, int lineNumber, long sequence, String message, String traceId, MatchResult match) {
        String logId = logId(lineNumber);
        Instant timestamp = BASE_TIME.plusSeconds(lineNumber);
        return new EnrichedLog(logId, timestamp, timestamp.toString(), DATASET_ID, sourceFile, lineNumber, 1, sequence,
            "customers-service", "spring-petclinic-customers-service", "app", "1", "thread-1", Level.INFO,
            "a.OwnerResource", "a.OwnerResource", message, message, traceId, traceId == null ? null : "span-" + sequence,
            null, new CodeVersion(CODE_UNIT.name(), CODE_UNIT.version()), match, null, "spring-boot-default", "test-seed", timestamp);
    }

    private static String logId(int lineNumber) {
        return org.log2code.core.ids.StableIds.logId(DATASET_ID, "logs/customers-service.log", lineNumber);
    }
}
