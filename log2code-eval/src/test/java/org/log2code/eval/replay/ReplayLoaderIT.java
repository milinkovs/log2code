package org.log2code.eval.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.Label;
import org.log2code.core.model.TypeInfo;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.ablate.AblationResult;
import org.log2code.eval.ablate.AblationRunner;
import org.log2code.eval.ablate.Variants;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T34 IT: {@link ReplayLoader} against a real OpenSearch and a real dataset folder - the catalog, analysis run and
 * labels come from the indexes, the events are re-read from an oracle log file by the ingester's own
 * assembler. The catalog is the three-statement one of {@link SyntheticDataset}, so the outcome of every ablation
 * variant is already known. {@code CatalogIndex.load} always reads the default index names, which this
 * container (one per test class) owns alone.
 */
@Testcontainers
class ReplayLoaderIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final Path CONFIG_DIR = Path.of("..", "config");
    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(CONFIG_DIR.resolve("matching.yml"));
    private static final String DATASET_ID = "syn-01";
    private static final String LOG_FILE = "logs/svc.log";

    private static OpenSearchClient client;

    @BeforeAll
    static void seedTheIndexes() throws IOException {
        client = OpenSearchClientFactory.create(OpenSearchConfig.of(OPENSEARCH.getHttpHostAddress()));
        IndexNames names = new IndexNames();
        IndexManager indexManager = new IndexManager(client, names);
        indexManager.ensureAll();

        var run = SyntheticDataset.run();
        client.index(i -> i.index(names.runs()).id(run.runId()).document(run));
        try (BulkWriter<CatalogEntry> catalog = new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            for (CatalogEntry entry : SyntheticDataset.entries()) {
                catalog.add(entry);
            }
            catalog.flush();
        }
        try (BulkWriter<TypeInfo> types = new BulkWriter<>(client, names.types(), TypeInfo::typeId)) {
            for (CatalogEntry entry : SyntheticDataset.entries()) {
                types.add(new TypeInfo("type-" + entry.statementId(), SyntheticDataset.PROJECT, "synthetic-mod", entry.filePath(),
                    entry.classFqn(), entry.classFqn(), null, List.of(), "class"));
            }
            types.flush();
        }
        for (String index : names.all()) {
            indexManager.refresh(index);
        }
    }

    @AfterAll
    static void closeTheClient() throws IOException {
        OpenSearchClientFactory.close(client);
    }

    @Test
    void replaysAnOracleDatasetFromItsFilesAndTheIndexes(@TempDir Path dir) throws IOException {
        Path dataset = datasetFolder(dir, DATASET_ID, "v1");

        ReplayDataset replay = ReplayLoader.load(client, new IndexNames(), dataset, CONFIG_DIR);

        assertThat(replay.datasetId()).isEqualTo(DATASET_ID);
        assertThat(replay.size()).isEqualTo(3);
        assertThat(replay.code()).isEqualTo(SyntheticDataset.CODE);
        List<EventEvaluation> evaluations = replay.evaluate(CONFIG, CandidateMode.BOTH);
        assertThat(evaluations).extracting(EventEvaluation::truthStatementIds)
            .containsExactly(List.of("s1"), List.of("s2"), List.of("s3"));
        assertThat(evaluations).extracting(EventEvaluation::predictedStatementId).containsExactly("s1", "s2", "s3");
        assertThat(evaluations).allMatch(EventEvaluation::correctAt1);
    }

    @Test
    void theAblationOverTheLoadedDatasetMatchesTheHandComputedOutcome(@TempDir Path dir) throws IOException {
        ReplayDataset replay = ReplayLoader.load(client, new IndexNames(), datasetFolder(dir, DATASET_ID, "v1"), CONFIG_DIR);

        AblationResult result = AblationRunner.run(replay, CONFIG, Variants.standard());

        assertThat(result.variants()).extracting(v -> v.variant().id() + "=" + v.score().correctAt1())
            .containsExactly("full=3", "no_logger=1", "no_level=2", "logger_candidates_only=3", "token_candidates_only=2",
                "no_specificity=3", "regex_only=1");
    }

    @Test
    void aManualLabelInTheLabelsIndexOverridesTheOracle(@TempDir Path dir) throws IOException {
        String logId = StableIds.logId("syn-labelled", LOG_FILE, 1);
        Label label = new Label(logId, "syn-labelled", Label.VERDICT_INCORRECT, "s2", "s1", null, Instant.EPOCH);
        client.index(i -> i.index(new IndexNames().labels()).id(label.logId()).document(label));
        new IndexManager(client, new IndexNames()).refresh(new IndexNames().labels());

        ReplayDataset replay = ReplayLoader.load(client, new IndexNames(), datasetFolder(dir, "syn-labelled", "v1"), CONFIG_DIR);

        EventEvaluation first = replay.evaluate(CONFIG, CandidateMode.BOTH).get(0);
        assertThat(first.truthStatementIds()).containsExactly("s2");
        assertThat(first.correctAt1()).isFalse();
    }

    @Test
    void aDatasetOfAnUnanalysedCodeVersionIsAUserError(@TempDir Path dir) throws IOException {
        Path dataset = datasetFolder(dir, "syn-unknown", "never-analysed");

        assertThatThrownBy(() -> ReplayLoader.load(client, new IndexNames(), dataset, CONFIG_DIR))
            .isInstanceOf(EvalUserException.class)
            .hasMessageContaining("analyzer project");
    }

    @Test
    void aMissingLogFileIsAUserError(@TempDir Path dir) throws IOException {
        Path dataset = datasetFolder(dir, DATASET_ID, "v1");
        Files.delete(dataset.resolve(LOG_FILE));

        assertThatThrownBy(() -> ReplayLoader.load(client, new IndexNames(), dataset, CONFIG_DIR))
            .isInstanceOf(EvalUserException.class)
            .hasMessageContaining("dataset log file not found");
    }

    /** A dataset folder with one oracle log file holding the three events of {@link SyntheticDataset}. */
    private static Path datasetFolder(Path root, String datasetId, String version) throws IOException {
        Path folder = Files.createDirectories(root.resolve(datasetId));
        Files.createDirectories(folder.resolve("logs"));
        Files.writeString(folder.resolve(LOG_FILE),
            line("INFO", "com.acme.A", "@@L2C[com.acme.A|m|10]@@ Saving x") + "\n"
                + line("INFO", "com.acme.B", "@@L2C[com.acme.B|m|20]@@ Saving y") + "\n"
                + line("INFO", "com.acme.C", "@@L2C[com.acme.C|m|30]@@ whatever") + "\n");
        Files.writeString(folder.resolve("manifest.yml"), """
            dataset_id: %s
            description: "T34 IT fixture"
            created_at: "2026-10-08T10:00:00+02:00"
            oracle: true
            log_format: spring-boot-default
            code:
              name: synthetic
              version: %s
            files:
              - path: %s
                service: svc
                module: synthetic-mod
            notes: ""
            """.formatted(datasetId, version, LOG_FILE));
        return folder;
    }

    /** One {@code spring-boot-default} line (0.9) without the optional app-name and correlation segments. */
    private static String line(String level, String logger, String message) {
        return "2026-10-08T10:00:00.000Z  " + level + " 1 --- [" + pad("main", 15) + "] " + pad(logger, 40) + " : " + message;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
    }
}
