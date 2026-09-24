package org.log2code.ingester.follow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.core.github.CodeUnitsConfig;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.TypeInfo;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.ingester.assemble.EventAssembler;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.cli.Wiring;
import org.log2code.ingester.enrich.StackFrameResolver;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.match.Matcher;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;
import org.log2code.ingester.parse.preset.SpringBootDefaultParser;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T22 end to end against a real OpenSearch: a file grows across several separate poll cycles (AC4's
 * "temp file appended to in multiple steps"), a multiline exception spanning three of those cycles is
 * not split into more than one event (AC2), an idle event with no following header is force-flushed
 * once {@code flush-timeout} elapses, and a brand new {@link FollowSession} resuming from the
 * persisted offsets after a simulated restart neither duplicates nor mis-numbers events (AC3, and the
 * deeper {@code line_number}/{@code log_id} continuity {@code FollowSession}'s replay exists for).
 */
@Testcontainers
class FollowSessionIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final Path REAL_MATCHING_CONFIG = Path.of("..", "config", "matching.yml");

    private static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-it", "v1");
    private static final String SERVICE = "customers-service";
    private static final String MODULE = "spring-petclinic-customers-service";
    private static final String OWNER_FQN = "org.log2code.fixture.OwnerResource";
    private static final String OWNER_FILE_PATH = MODULE + "/src/main/java/org/log2code/fixture/OwnerResource.java";
    private static final String LOG_PATH = "data/logs/customers-service.log";

    private static OpenSearchClient client;

    @BeforeAll
    static void setUpClient() {
        client = OpenSearchClientFactory.create(OpenSearchConfig.of(OPENSEARCH.getHttpHostAddress()));
    }

    @AfterAll
    static void tearDownClient() throws IOException {
        OpenSearchClientFactory.close(client);
    }

    @Test
    void followsAGrowingFileAcrossPollsWithoutSplittingExceptionsAndSurvivesARestart(@TempDir Path repoRoot) throws Exception {
        IndexNames indexNames = new IndexNames("it-follow-" + UUID.randomUUID() + "-");
        IndexManager indexManager = new IndexManager(client, indexNames);
        indexManager.ensureAll();
        seedCatalog(indexNames);

        DatasetManifest manifest = liveManifest();
        Wiring.Components components = buildComponents();
        Path offsetsFile = repoRoot.resolve("offsets.json");
        Path logFile = repoRoot.resolve(LOG_PATH);
        Files.createDirectories(logFile.getParent());

        // ---- poll before the file even exists yet (a service that hasn't logged anything yet) ------
        FollowSession session = FollowSession.open(client, indexNames, manifest, components, repoRoot, offsetsFile, Duration.ofMillis(150));
        FollowSession.PollResult beforeFileExists = session.pollOnce();
        assertThat(beforeFileExists.written()).isZero();
        assertThat(beforeFileExists.filesMissing()).isEqualTo(1);

        // ---- the "Saving owner" event arrives in one write; nothing is emitted until a header follows
        Files.writeString(logFile, header("2026-09-24T10:00:00.000Z", "INFO", "Saving owner 42") + "\n");
        assertThat(session.pollOnce().written()).isZero();

        // ---- the ERROR header closes the "Saving owner" event out ------------------------------------
        append(logFile, header("2026-09-24T10:00:01.000Z", "ERROR", "boom") + "\n");
        assertThat(session.pollOnce().written()).isEqualTo(1);

        // ---- the exception's two frame lines arrive across two SEPARATE poll cycles (AC4) - still open
        append(logFile, "java.lang.RuntimeException: boom\n");
        assertThat(session.pollOnce().written()).isZero();
        append(logFile, "\tat org.log2code.fixture.OwnerResource.updateOwner(OwnerResource.java:89)\n");
        assertThat(session.pollOnce().written()).isZero();

        // ---- no next header ever comes; once flush-timeout elapses, it is written anyway (AC2/T22) ---
        Thread.sleep(200);
        assertThat(session.pollOnce().written()).isEqualTo(1);

        indexManager.refresh(indexNames.logs());
        assertThat(indexManager.count(indexNames.logs(), Map.of("dataset_id", "live"))).isEqualTo(2);

        DocumentReader reader = new DocumentReader(client);
        EnrichedLog matchedDoc = reader.get(indexNames.logs(), StableIds.logId("live", LOG_PATH, 1), EnrichedLog.class);
        assertThat(matchedDoc.match().status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(matchedDoc.match().statementId()).isEqualTo("stmt-owner");

        EnrichedLog errorDoc = reader.get(indexNames.logs(), StableIds.logId("live", LOG_PATH, 2), EnrichedLog.class);
        assertThat(errorDoc.message()).isEqualTo("boom");
        assertThat(errorDoc.lineCount()).isEqualTo(3); // header + both exception lines - not split (AC2)
        assertThat(errorDoc.exception()).isNotNull();
        assertThat(errorDoc.exception().frames()).hasSize(1);

        session.shutdown();

        // ---- simulated restart: a brand new session resumes from the persisted offsets ---------------
        FollowSession restarted = FollowSession.open(client, indexNames, manifest, components, repoRoot, offsetsFile, Duration.ofMillis(150));
        assertThat(restarted.pollOnce().written()).isZero(); // nothing new yet - no duplicate re-write
        indexManager.refresh(indexNames.logs());
        assertThat(indexManager.count(indexNames.logs(), Map.of("dataset_id", "live"))).isEqualTo(2); // AC3

        // a new event appended after the restart must continue numbering, not collide with line 1
        append(logFile, header("2026-09-24T10:00:05.000Z", "INFO", "Saving owner 99") + "\n");
        Thread.sleep(200); // idle past flush-timeout - no next header will ever close it
        assertThat(restarted.pollOnce().written()).isEqualTo(1);
        restarted.shutdown();

        indexManager.refresh(indexNames.logs());
        assertThat(indexManager.count(indexNames.logs(), Map.of("dataset_id", "live"))).isEqualTo(3);
        EnrichedLog thirdDoc = reader.get(indexNames.logs(), StableIds.logId("live", LOG_PATH, 5), EnrichedLog.class);
        assertThat(thirdDoc).isNotNull();
        assertThat(thirdDoc.message()).isEqualTo("Saving owner 99");
        assertThat(thirdDoc.match().status()).isEqualTo(MatchResult.STATUS_MATCHED);
    }

    private void seedCatalog(IndexNames indexNames) throws IOException {
        AnalysisRun run = new AnalysisRun("run-it-follow", CodeUnit.TYPE_PROJECT, PROJECT,
            "https://example.invalid/petclinic-it", "test-analyzer",
            Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:05Z"), 5000, Map.of(),
            List.of(new ModuleInfo(MODULE, SERVICE, List.of("src/main/java"), List.of(), List.of())));
        CatalogEntry ownerEntry = ownerCatalogEntry();
        TypeInfo ownerType = new TypeInfo("type-owner", PROJECT, MODULE, OWNER_FILE_PATH, OWNER_FQN, OWNER_FQN, null, List.of(), "class");

        client.index(i -> i.index(indexNames.runs()).id(run.runId()).document(run));
        try (BulkWriter<CatalogEntry> catalogWriter = new BulkWriter<>(client, indexNames.catalog(), CatalogEntry::statementId)) {
            catalogWriter.add(ownerEntry);
            catalogWriter.flush();
        }
        try (BulkWriter<TypeInfo> typesWriter = new BulkWriter<>(client, indexNames.types(), TypeInfo::typeId)) {
            typesWriter.add(ownerType);
            typesWriter.flush();
        }
        IndexManager indexManager = new IndexManager(client, indexNames);
        indexManager.refresh(indexNames.runs());
        indexManager.refresh(indexNames.catalog());
        indexManager.refresh(indexNames.types());
    }

    private Wiring.Components buildComponents() throws IOException {
        AnalysisRun run = new AnalysisRun("run-it-follow", CodeUnit.TYPE_PROJECT, PROJECT,
            "https://example.invalid/petclinic-it", "test-analyzer",
            Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:05Z"), 5000, Map.of(),
            List.of(new ModuleInfo(MODULE, SERVICE, List.of("src/main/java"), List.of(), List.of())));
        CatalogEntry ownerEntry = ownerCatalogEntry();
        TypeInfo ownerType = new TypeInfo("type-owner", PROJECT, MODULE, OWNER_FILE_PATH, OWNER_FQN, OWNER_FQN, null, List.of(), "class");

        CatalogIndex catalogIndex = CatalogIndex.build(run, List.of(ownerEntry), List.of(ownerType));
        MatchingConfig matchingConfig = MatchingConfigLoader.load(REAL_MATCHING_CONFIG);
        Matcher matcher = new Matcher(catalogIndex, matchingConfig);
        EventAssembler assembler = new EventAssembler(new SpringBootDefaultParser(), matchingConfig.oracle().unreliableCallers());
        GithubLinker linker = new GithubLinker(new CodeUnitsConfig(
            Map.of("petclinic-it", new CodeUnitsConfig.ProjectMapping("https://github.com/example/petclinic-it", "{version}", "{file_path}")),
            List.of()), null);
        StackFrameResolver frameResolver = new StackFrameResolver(catalogIndex, linker, fileId -> false);
        return new Wiring.Components(catalogIndex, matcher, assembler, frameResolver);
    }

    private static DatasetManifest liveManifest() {
        return new DatasetManifest("live", "T22 IT fixture", "2026-09-24T10:00:00+02:00", false, "spring-boot-default",
            new CodeVersion("petclinic-it", "v1"),
            List.of(new DatasetManifest.FileEntry(LOG_PATH, SERVICE, MODULE)), "");
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String header(String timestamp, String level, String message) {
        return timestamp + "  " + level + " 1 --- [" + fixedWidth("nio-8081-exec-1", 15) + "] "
            + fixedWidth(OWNER_FQN, 40) + " : " + message;
    }

    private static String fixedWidth(String value, int width) {
        return value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
    }

    private static CatalogEntry ownerCatalogEntry() {
        String template = "Saving owner {}";
        return new CatalogEntry(
            "stmt-owner", "stmt-owner-logical", PROJECT, MODULE, SERVICE,
            "OwnerResource.java", "file-owner", "org.log2code.fixture", OWNER_FQN, OWNER_FQN,
            "updateOwner", "updateOwner(int)", null, false,
            89, 89, 8, 85, 95,
            "slf4j", "typed", "log", OWNER_FQN, "class_literal",
            Level.INFO, false,
            "\"" + template + "\"", template, "placeholders", null, null, List.of(), template.length(), 1, false,
            new EnclosingBlock("method", null, null, 85, 95), null,
            "    log.info(\"Saving owner {}\", ownerId);\n", 87,
            "https://github.com/example/petclinic-it/blob/v1/" + OWNER_FILE_PATH + "#L89",
            "test-analyzer", Instant.parse("2026-09-24T10:00:00Z"));
    }
}
