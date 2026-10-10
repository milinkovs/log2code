package org.log2code.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.ExplainPromptDto;
import org.log2code.api.dto.ExplainSectionDto;
import org.log2code.core.ids.StableIds;
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
 * T41 IT: {@code GET /api/logs/{logId}/explain/prompt} against a real OpenSearch, seeded directly (same
 * pattern as {@link ContextApiIT}): the whole path from the indexed documents through the prompt builder and
 * renderer to the JSON of {@link ExplainPromptDto}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ExplainApiIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final IndexNames INDEX_NAMES = new IndexNames();
    private static final String DATASET_ID = "it-explain-01";
    private static final String LOG_FILE = "logs/customers-service.log";
    private static final Instant BASE_TIME = Instant.parse("2026-09-25T10:00:00Z");
    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-it-explain", "0123456789abcdef");
    private static final String OWNER_FILE_CONTENT = String.join("\n",
        "package a;",                                // 1
        "class OwnerResource {",                     // 2
        "  void updateOwner() {",                    // 3
        "    validate();",                           // 4
        "    log.info(\"Saving owner {}\", owner);", // 5
        "    save(owner);",                          // 6
        "  }",                                       // 7
        "}");                                        // 8
    private static final String MAPPER_FILE_CONTENT = String.join("\n",
        "package a;",          // 1
        "class OwnerMapper {", // 2
        "  Owner map() {",     // 3
        "    return owner;",   // 4
        "  }",                 // 5
        "}");                  // 6

    private static OpenSearchClient seedClient;
    private static final String OWNER_FILE_ID = "explain-owner-file";
    private static final String MAPPER_FILE_ID = "explain-mapper-file";
    private static String withExceptionLogId;
    private static String unmatchedLogId;

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

        seedClient.index(i -> i.index(INDEX_NAMES.sources()).id(OWNER_FILE_ID).document(
            new SourceFile(OWNER_FILE_ID, CODE_UNIT, "spring-petclinic-customers-service", "OwnerResource.java", OWNER_FILE_CONTENT, 8, "sha-owner")));
        seedClient.index(i -> i.index(INDEX_NAMES.sources()).id(MAPPER_FILE_ID).document(
            new SourceFile(MAPPER_FILE_ID, CODE_UNIT, "spring-petclinic-customers-service", "OwnerMapper.java", MAPPER_FILE_CONTENT, 6, "sha-mapper")));

        ControlContext control = new ControlContext(List.of(new Condition("if", "owner != null", 4, false)), List.of(), List.of(), List.of());
        CatalogEntry entry = new CatalogEntry(
            "stmt-owner", "logical-owner", CODE_UNIT, "spring-petclinic-customers-service", "customers-service",
            "OwnerResource.java", OWNER_FILE_ID, "a", "a.OwnerResource", "OwnerResource", "updateOwner", "updateOwner()",
            "method-update-owner", false, 5, 5, 4, 3, 7, "slf4j", "typed", "log", "OwnerResource", "class_literal",
            Level.INFO, false, "\"Saving owner {}\"", "Saving owner {}", "placeholders", null, "^Saving owner (.*)$",
            List.of("Saving", "owner"), 12, 1, false, new EnclosingBlock("method", null, null, 3, 7), control,
            "log.info(\"Saving owner {}\", owner);", 5, "https://github.com/x/y/blob/0123456/OwnerResource.java#L5",
            "0.1.0-SNAPSHOT", BASE_TIME);
        seedClient.index(i -> i.index(INDEX_NAMES.catalog()).id(entry.statementId()).document(entry));

        MethodInfo updateOwner = new MethodInfo("method-update-owner", CODE_UNIT, "spring-petclinic-customers-service",
            "customers-service", OWNER_FILE_ID, "OwnerResource.java", "a.OwnerResource", "OwnerResource", "updateOwner",
            "updateOwner()", 3, 7, List.of(), true, List.of(),
            List.of(new CallerRef("method-map", "a.OwnerMapper", "map", MAPPER_FILE_ID, 3)), 1);
        MethodInfo map = new MethodInfo("method-map", CODE_UNIT, "spring-petclinic-customers-service",
            "customers-service", MAPPER_FILE_ID, "OwnerMapper.java", "a.OwnerMapper", "OwnerMapper", "map", "map()",
            2, 5, List.of("PostMapping"), false, List.of(), List.of(), 0);
        try (BulkWriter<MethodInfo> writer = new BulkWriter<>(seedClient, INDEX_NAMES.methods(), MethodInfo::methodId)) {
            writer.add(updateOwner);
            writer.add(map);
            writer.flush();
        }

        seedLogs();
        for (String index : List.of(INDEX_NAMES.sources(), INDEX_NAMES.catalog(), INDEX_NAMES.methods(), INDEX_NAMES.logs())) {
            indexManager.refresh(index);
        }
    }

    private ExplainPromptDto prompt(String logId, String query) {
        return restTemplate.getForObject("/api/logs/" + logId + "/explain/prompt" + query, ExplainPromptDto.class);
    }

    private static List<String> headings(String text) {
        return text.lines().filter(l -> l.startsWith("#")).toList();
    }

    private static ExplainSectionDto section(ExplainPromptDto prompt, String id) {
        return prompt.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void l4PromptIsBuiltFromTheIndexedDocuments() {
        ExplainPromptDto prompt = prompt(withExceptionLogId, "?level=L4");

        assertThat(prompt.promptVersion()).isEqualTo(1);
        assertThat(prompt.level()).isEqualTo("L4");
        assertThat(prompt.systemPrompt()).startsWith("Ti si iskusan Java inženjer.");
        assertThat(prompt.promptChars()).isEqualTo(prompt.userPrompt().length());
        assertThat(prompt.sections()).extracting(ExplainSectionDto::id)
            .containsExactly("log", "exception", "statement", "method", "flow", "stackCode", "callers", "neighbors");
        assertThat(prompt.sections()).allMatch(s -> s.included() && s.reason() == null);


        assertThat(headings(prompt.userPrompt())).containsExactly(
            "# Log zapis", "## Izuzetak",
            "# Mesto u kodu koje je napisalo ovaj log", "## Metoda u kojoj je log",
            "# Uslovi i tok do loga",
            "# Kod iz stack trace-a", "### OwnerResource.updateOwner — OwnerResource.java:5",
            "# Pozivaoci", "### Nivo 1: a.OwnerMapper#map() — OwnerMapper.java, poziv u liniji 3, REST ulaz (@PostMapping)",
            "# Susedni logovi istog servisa");

        String text = prompt.userPrompt();
        assertThat(text).contains("- Nivo: ERROR", "- Poruka: boom");
        assertThat(text).contains("""
            ```text
            java.lang.IllegalStateException: boom
            \tat a.OwnerResource.updateOwner(OwnerResource.java:5)
            ```
            """);
        assertThat(text).contains("""
            - Povezivanje: matched, pouzdanost high (0.95)
            - Kod: projekat petclinic-it-explain @ 0123456
            - Fajl: OwnerResource.java, linija 5
            - Klasa i metoda: a.OwnerResource#updateOwner()
            - Šablon poruke: Saving owner {}
            """);
        assertThat(text).contains("""
               3 |   void updateOwner() {
               4 |     validate();
               5 |     log.info("Saving owner {}", owner);
               6 |     save(owner);
               7 |   }
            """);
        assertThat(text).contains("- Unutar: if (owner != null) (linija 4)");
        assertThat(text).contains("""
            ```text
            10:00:00.000 INFO a.OwnerResource — Saving owner Owner[0]
            ▶ 10:00:01.000 ERROR a.OwnerResource — boom
            10:00:02.000 INFO a.OwnerResource — Saving owner Owner[2]
            ```
            """);
    }

    @Test
    void defaultLevelIsL2AndL0HasOnlyTheLog() {
        ExplainPromptDto byDefault = prompt(withExceptionLogId, "");
        assertThat(byDefault.level()).isEqualTo("L2");
        assertThat(section(byDefault, "flow").included()).isTrue();
        assertThat(section(byDefault, "callers")).extracting(ExplainSectionDto::included, ExplainSectionDto::reason).containsExactly(false, "level");

        ExplainPromptDto l0 = prompt(withExceptionLogId, "?level=L0");
        assertThat(headings(l0.userPrompt())).containsExactly("# Log zapis", "## Izuzetak");
        assertThat(section(l0, "statement")).extracting(ExplainSectionDto::included, ExplainSectionDto::reason).containsExactly(false, "level");
    }

    @Test
    void unmatchedLogHasOnlyTheLogAndTheReasonIsReportedForTheRest() {
        ExplainPromptDto prompt = prompt(unmatchedLogId, "?level=L4");

        assertThat(headings(prompt.userPrompt())).containsExactly("# Log zapis", "# Susedni logovi istog servisa");
        assertThat(section(prompt, "statement").reason()).isEqualTo("unmatched");
        assertThat(section(prompt, "method").reason()).isEqualTo("unmatched");
        assertThat(section(prompt, "callers").reason()).isEqualTo("unmatched");
        assertThat(section(prompt, "exception").reason()).isEqualTo("noException");
        assertThat(section(prompt, "neighbors").included()).isTrue();
    }

    @Test
    void invalidLevelIs400AndUnknownLogIs404() {
        ResponseEntity<String> bad = restTemplate.getForEntity("/api/logs/" + withExceptionLogId + "/explain/prompt?level=INVALID", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("level must be one of L0, L1, L2, L3, L4");

        ResponseEntity<String> missing = restTemplate.getForEntity("/api/logs/does-not-exist/explain/prompt", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).contains("log not found: does-not-exist");
    }

    // ---- seeding ----

    private static void seedLogs() throws IOException {
        try (BulkWriter<EnrichedLog> writer = new BulkWriter<>(seedClient, INDEX_NAMES.logs(), EnrichedLog::logId)) {
            MatchResult matched = new MatchResult(MatchResult.STATUS_MATCHED, "stmt-owner", 0.95, MatchResult.CONFIDENCE_HIGH,
                List.of(new Candidate("stmt-owner", 0.95)), Map.of(), List.of("Owner[1]"),
                CODE_UNIT.name(), "customers-service", "a.OwnerResource", "updateOwner", "OwnerResource.java",
                "slf4j", "placeholders", 5, "Saving owner {}", null);
            MatchResult unmatched = new MatchResult(MatchResult.STATUS_UNMATCHED, null, 0.1, MatchResult.CONFIDENCE_LOW,
                List.of(), Map.of(), List.of(), null, null, null, null, null, null, null, null, null, null);

            writer.add(tickEvent(0, "Saving owner Owner[0]", matched));

            StackFrame projectFrame = new StackFrame("a.OwnerResource", "updateOwner", "OwnerResource.java", 5, true,
                CODE_UNIT.name() + ":" + CODE_UNIT.version(), OWNER_FILE_ID, null);
            ExceptionInfo exception = new ExceptionInfo("java.lang.IllegalStateException", "java.lang.IllegalStateException", "boom",
                List.of(projectFrame), List.of(new CausedBy("java.lang.RuntimeException", "root", List.of(projectFrame))));
            Instant at = BASE_TIME.plusSeconds(1);
            String raw = at + " ERROR 1 --- [customers-service] [thread-1] a.OwnerResource : boom\n"
                + "java.lang.IllegalStateException: boom\n\tat a.OwnerResource.updateOwner(OwnerResource.java:5)";
            EnrichedLog withException = new EnrichedLog(logId(1), at, at.toString(), DATASET_ID, LOG_FILE, 1, 3, 1,
                "customers-service", "spring-petclinic-customers-service", "app", "1", "thread-1", Level.ERROR,
                "a.OwnerResource", "a.OwnerResource", "boom", raw, null, null, exception,
                new CodeVersion(CODE_UNIT.name(), CODE_UNIT.version()), matched, null, "spring-boot-default", "test-seed", at);
            withExceptionLogId = withException.logId();
            writer.add(withException);

            EnrichedLog unmatchedLog = tickEvent(2, "Saving owner Owner[2]", unmatched);
            unmatchedLogId = unmatchedLog.logId();
            writer.add(unmatchedLog);
            writer.flush();
        }
    }

    private static EnrichedLog tickEvent(int lineNumber, String message, MatchResult match) {
        Instant timestamp = BASE_TIME.plusSeconds(lineNumber);
        return new EnrichedLog(logId(lineNumber), timestamp, timestamp.toString(), DATASET_ID, LOG_FILE, lineNumber, 1, lineNumber,
            "customers-service", "spring-petclinic-customers-service", "app", "1", "thread-1", Level.INFO,
            "a.OwnerResource", "a.OwnerResource", message, message, null, null, null,
            new CodeVersion(CODE_UNIT.name(), CODE_UNIT.version()), match, null, "spring-boot-default", "test-seed", timestamp);
    }

    private static String logId(int lineNumber) {
        return StableIds.logId(DATASET_ID, LOG_FILE, lineNumber);
    }
}
