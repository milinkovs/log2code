package org.log2code.ingester.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.log2code.core.model.StackFrame;
import org.log2code.core.model.TypeInfo;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.ingester.assemble.EventAssembler;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.enrich.StackFrameResolver;
import org.log2code.ingester.explain.ExplainRunner;
import org.log2code.ingester.manifest.DatasetManifest;
import org.log2code.ingester.manifest.ManifestLoader;
import org.log2code.ingester.match.Matcher;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;
import org.log2code.ingester.parse.preset.SpringBootDefaultParser;
import org.log2code.ingester.stats.DatasetStats;
import org.log2code.ingester.stats.StatsRunner;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T21 IT: the whole {@code ingest} pipeline end to end against a real OpenSearch - a mini catalog
 * (one project statement) and a mini dataset (one file, two events: one that matches it and one
 * unmatched event carrying an exception whose one frame resolves back to the same project class) -
 * plus {@code stats} and {@code explain} against the result. Covers T21's own "Testovi" list: field
 * population (including this task's own denormalization and stack-frame-resolution steps), the
 * document count matching the report, double-ingest idempotency and {@code --recreate-dataset}.
 */
@Testcontainers
class IngestRunnerAssemblyIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final Path REAL_MATCHING_CONFIG = Path.of("..", "config", "matching.yml");

    private static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-it", "v1");
    private static final String SERVICE = "customers-service";
    private static final String MODULE = "spring-petclinic-customers-service";
    private static final String OWNER_FQN = "org.log2code.fixture.OwnerResource";
    private static final String OWNER_FILE_PATH = MODULE + "/src/main/java/org/log2code/fixture/OwnerResource.java";
    private static final String LOG_FILE_PATH = "logs/customers-service.log";

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
    void ingestsMatchesEnrichesAndIsIdempotentThenRecreateDatasetStartsClean(@TempDir Path tempDir) throws IOException {
        IndexNames indexNames = new IndexNames("it-" + UUID.randomUUID() + "-");
        IndexManager indexManager = new IndexManager(client, indexNames);
        indexManager.ensureAll();

        AnalysisRun run = new AnalysisRun("run-it-1", CodeUnit.TYPE_PROJECT, PROJECT,
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
        indexManager.refresh(indexNames.runs());
        indexManager.refresh(indexNames.catalog());
        indexManager.refresh(indexNames.types());

        String datasetId = "it-ingest-01";
        Path datasetDir = tempDir.resolve(datasetId);
        Files.createDirectories(datasetDir.resolve("logs"));
        Files.writeString(datasetDir.resolve(LOG_FILE_PATH), sampleLogContent());
        Files.writeString(datasetDir.resolve("manifest.yml"), manifestYaml(datasetId));
        DatasetManifest manifest = ManifestLoader.load(datasetDir.resolve("manifest.yml"));

        CatalogIndex catalogIndex = CatalogIndex.build(run, List.of(ownerEntry), List.of(ownerType));
        MatchingConfig matchingConfig = MatchingConfigLoader.load(REAL_MATCHING_CONFIG);
        Matcher matcher = new Matcher(catalogIndex, matchingConfig);
        EventAssembler assembler = new EventAssembler(new SpringBootDefaultParser(), matchingConfig.oracle().unreliableCallers());
        GithubLinker linker = new GithubLinker(new CodeUnitsConfig(
            Map.of("petclinic-it", new CodeUnitsConfig.ProjectMapping("https://github.com/example/petclinic-it", "{version}", "{file_path}")),
            List.of()), null);
        StackFrameResolver frameResolver = new StackFrameResolver(catalogIndex, linker, fileId -> false);

        // ---- first ingest: field checks, and doc count == report's total ----
        IngestReport report1 = IngestRunner.run(client, indexNames, manifest, datasetDir, assembler, catalogIndex,
            matcher, frameResolver, false, 100);
        indexManager.refresh(indexNames.logs());

        assertThat(report1.totalEvents()).isEqualTo(2);
        assertThat(indexManager.count(indexNames.logs(), Map.of("dataset_id", datasetId))).isEqualTo(report1.totalEvents());
        assertThat(report1.byStatus().get(MatchResult.STATUS_MATCHED)).isEqualTo(1L);
        assertThat(report1.byStatus().get(MatchResult.STATUS_UNMATCHED)).isEqualTo(1L);
        assertThat(report1.withException()).isEqualTo(1L);

        DocumentReader reader = new DocumentReader(client);
        EnrichedLog matchedDoc = reader.get(indexNames.logs(), StableIds.logId(datasetId, LOG_FILE_PATH, 1), EnrichedLog.class);
        assertThat(matchedDoc).isNotNull();
        assertThat(matchedDoc.match().status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(matchedDoc.match().statementId()).isEqualTo("stmt-owner");
        // T21 step 3: match.* denormalized from the winning catalog entry, not left null as T20 leaves them.
        assertThat(matchedDoc.match().codeUnit()).isEqualTo("petclinic-it");
        assertThat(matchedDoc.match().classFqn()).isEqualTo(OWNER_FQN);
        assertThat(matchedDoc.match().methodName()).isEqualTo("updateOwner");
        assertThat(matchedDoc.match().loggingApi()).isEqualTo("slf4j");
        assertThat(matchedDoc.match().githubUrl()).isEqualTo("https://github.com/example/petclinic-it/blob/v1/" + OWNER_FILE_PATH + "#L89");
        // T21: EnrichedLog.logger, resolved unambiguously (0.10 step 1) since it is the only known name.
        assertThat(matchedDoc.logger()).isEqualTo(OWNER_FQN);
        assertThat(matchedDoc.ingesterVersion()).isNotNull();

        EnrichedLog errorDoc = reader.get(indexNames.logs(), StableIds.logId(datasetId, LOG_FILE_PATH, 2), EnrichedLog.class);
        assertThat(errorDoc).isNotNull();
        assertThat(errorDoc.match().status()).isEqualTo(MatchResult.STATUS_UNMATCHED);
        assertThat(errorDoc.exception()).isNotNull();
        StackFrame frame = errorDoc.exception().frames().get(0);
        // T21's own "Rezolucija stack frame-ova" step: a frame pointing back at the same project class.
        assertThat(frame.inProject()).isTrue();
        assertThat(frame.codeUnit()).isEqualTo("petclinic-it");
        assertThat(frame.fileId()).isEqualTo(StableIds.fileId("petclinic-it", "v1", OWNER_FILE_PATH));
        assertThat(frame.githubUrl()).isEqualTo("https://github.com/example/petclinic-it/blob/v1/" + OWNER_FILE_PATH + "#L89");

        // ---- idempotency: re-ingesting the same dataset does not change _count ----
        IngestRunner.run(client, indexNames, manifest, datasetDir, assembler, catalogIndex, matcher, frameResolver, false, 100);
        indexManager.refresh(indexNames.logs());
        assertThat(indexManager.count(indexNames.logs(), Map.of("dataset_id", datasetId))).isEqualTo(report1.totalEvents());

        // ---- stats: aggregations over what was just ingested ----
        DatasetStats stats = StatsRunner.compute(client, indexNames, datasetId);
        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.byStatus()).containsEntry(MatchResult.STATUS_MATCHED, 1L).containsEntry(MatchResult.STATUS_UNMATCHED, 1L);
        assertThat(stats.byService()).containsEntry(SERVICE, 2L);
        assertThat(stats.withException()).isEqualTo(1L);
        assertThat(stats.withTraceId()).isZero();

        // ---- explain: the matcher's own explanation for the matched event ----
        String explanation = ExplainRunner.explain(manifest, datasetDir, assembler, matcher, LOG_FILE_PATH, 1);
        assertThat(explanation).contains("stmt-owner").contains("status=matched");

        // ---- --recreate-dataset: deletes existing docs for this dataset_id before re-writing ----
        IngestReport report3 = IngestRunner.run(client, indexNames, manifest, datasetDir, assembler, catalogIndex,
            matcher, frameResolver, true, 100);
        indexManager.refresh(indexNames.logs());
        assertThat(indexManager.count(indexNames.logs(), Map.of("dataset_id", datasetId))).isEqualTo(report3.totalEvents());
    }

    /**
     * Two events in {@code spring-boot-default} format (T17): a matched "Saving owner" line (line 1),
     * and an ERROR event (line 2) whose message is followed by an unrelated exception (lines 3-4) - the
     * common real-world shape of {@code log.error("...", ex)}, per {@code docs/log-format.md}.
     */
    private static String sampleLogContent() {
        return header("2026-09-24T10:00:00.000Z", "INFO", "1", "nio-8081-exec-1", OWNER_FQN, "Saving owner 42") + "\n"
            + header("2026-09-24T10:00:01.000Z", "ERROR", "1", "nio-8081-exec-1", OWNER_FQN, "boom") + "\n"
            + "java.lang.RuntimeException: boom\n"
            + "\tat org.log2code.fixture.OwnerResource.updateOwner(OwnerResource.java:89)\n";
    }

    /** Builds one {@code spring-boot-default} header line, with no app-name/correlation segments (both optional, 0.9). */
    private static String header(String timestamp, String level, String pid, String thread, String logger, String message) {
        return timestamp + "  " + level + " " + pid + " --- [" + fixedWidth(thread, 15) + "] " + fixedWidth(logger, 40) + " : " + message;
    }

    private static String fixedWidth(String value, int width) {
        return value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
    }

    private static String manifestYaml(String datasetId) {
        return "dataset_id: " + datasetId + "\n"
            + "description: \"T21 IT fixture\"\n"
            + "created_at: \"2026-09-24T10:00:00+02:00\"\n"
            + "oracle: false\n"
            + "log_format: spring-boot-default\n"
            + "code:\n"
            + "  name: petclinic-it\n"
            + "  version: v1\n"
            + "files:\n"
            + "  - path: " + LOG_FILE_PATH + "\n"
            + "    service: " + SERVICE + "\n"
            + "    module: " + MODULE + "\n"
            + "notes: \"\"\n";
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
            // T15's analyzer already computed and stored this at catalog-write time; T21 only copies
            // it into match.github_url (0.7: "polja match.* se denormalizuju iz naredbe iz kataloga").
            "https://github.com/example/petclinic-it/blob/v1/" + OWNER_FILE_PATH + "#L89",
            "test-analyzer", Instant.parse("2026-09-24T10:00:00Z"));
    }
}
