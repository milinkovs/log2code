package org.log2code.eval.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.log2code.eval.TestData.CODE;
import static org.log2code.eval.TestData.dependency;
import static org.log2code.eval.TestData.dependencyStatement;
import static org.log2code.eval.TestData.label;
import static org.log2code.eval.TestData.log;
import static org.log2code.eval.TestData.match;
import static org.log2code.eval.TestData.module;
import static org.log2code.eval.TestData.projectStatement;
import static org.log2code.eval.TestData.reliable;
import static org.log2code.eval.TestData.run;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Label;
import org.log2code.core.model.Level;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.log2code.eval.EvalResult;
import org.log2code.eval.EvalRunner;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.truth.CatalogView;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T33 IT: {@link OpenSearchEvalDataSource} against a real OpenSearch - the dataset filter on events and
 * labels, the catalog restricted to the project plus the selected dependencies, a missing index treated as
 * empty, and a whole {@code EvalRunner} run on top of it.
 */
@Testcontainers
class OpenSearchEvalDataSourceIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final CodeUnit LIB_SELECTED = dependency("lib:selected", "1");
    private static final CodeUnit LIB_OTHER_VERSION = dependency("lib:selected", "2");
    private static final CodeUnit LIB_UNSELECTED = dependency("lib:unselected", "1");

    private static OpenSearchClient client;

    @BeforeAll
    static void setUpClient() {
        client = OpenSearchClientFactory.create(OpenSearchConfig.of(OPENSEARCH.getHttpHostAddress()));
    }

    @AfterAll
    static void tearDownClient() throws IOException {
        OpenSearchClientFactory.close(client);
    }

    private static IndexNames seededIndices() throws IOException {
        IndexNames names = new IndexNames("it-" + UUID.randomUUID() + "-");
        IndexManager indexManager = new IndexManager(client, names);
        indexManager.ensureAll();

        AnalysisRun run = run(module("svc", "lib:selected:1"));
        client.index(i -> i.index(names.runs()).id(run.runId()).document(run));

        List<CatalogEntry> catalog = List.of(
            projectStatement("p1", "svc", "p.C", "p.C", "m", 10, 10),
            dependencyStatement("d1", LIB_SELECTED, "lib.U", "go", 5, 5),
            dependencyStatement("d2", LIB_OTHER_VERSION, "lib.U", "go", 5, 5),    // same artifact, a version nobody selected
            dependencyStatement("d3", LIB_UNSELECTED, "lib.V", "go", 7, 7));
        for (CatalogEntry entry : catalog) {
            client.index(i -> i.index(names.catalog()).id(entry.statementId()).document(entry));
        }

        // two datasets in the same index; the unsorted ids check that the data source orders by file and line
        List<EnrichedLog> logs = List.of(
            log("ds-a-2", "svc", reliable("lib.U", 5), match("matched", "d1", "high", "d1")),
            log("ds-a-1", "svc", reliable("p.C", 10), match("matched", "p1", "high", "p1")),
            log("ds-a-3", "svc", reliable("p.C", 10), match("unmatched", null, null)));
        for (EnrichedLog log : logs) {
            client.index(i -> i.index(names.logs()).id(log.logId()).document(log));
        }
        EnrichedLog other = new EnrichedLog("ds-b-1", null, null, "ds-b", "logs/svc.log", 1, 1, 0, "svc", "m", null, null, null,
            Level.INFO, "l", null, "m", "r", null, null, null, CODE, null, null, "p", "t", null);
        client.index(i -> i.index(names.logs()).id(other.logId()).document(other));

        Label labelA = label("ds-a-3", Label.VERDICT_INCORRECT, "d1", null);
        client.index(i -> i.index(names.labels()).id(labelA.logId()).document(labelA));
        Label labelB = new Label("ds-b-1", "ds-b", Label.VERDICT_NOT_IN_CATALOG, null, null, null, null);
        client.index(i -> i.index(names.labels()).id(labelB.logId()).document(labelB));

        for (String index : names.all()) {
            indexManager.refresh(index);
        }
        return names;
    }

    @Test
    void readsOnlyTheRequestedDatasetInFileAndLineOrder() throws IOException {
        OpenSearchEvalDataSource source = new OpenSearchEvalDataSource(client, seededIndices());

        List<EnrichedLog> events = source.events("ds");

        assertThat(events).extracting(EnrichedLog::logId).containsExactlyInAnyOrder("ds-a-1", "ds-a-2", "ds-a-3");
        assertThat(events).extracting(EnrichedLog::lineNumber).isSorted();
        assertThat(source.events("ds-b")).extracting(EnrichedLog::logId).containsExactly("ds-b-1");
        assertThat(source.events("nope")).isEmpty();
    }

    @Test
    void readsLabelsOfTheRequestedDatasetByLogId() throws IOException {
        OpenSearchEvalDataSource source = new OpenSearchEvalDataSource(client, seededIndices());

        Map<String, Label> labels = source.labels("ds");

        assertThat(labels).containsOnlyKeys("ds-a-3");
        assertThat(labels.get("ds-a-3").verdict()).isEqualTo(Label.VERDICT_INCORRECT);
        assertThat(labels.get("ds-a-3").correctStatementId()).isEqualTo("d1");
        assertThat(source.labels("ds-b")).containsOnlyKeys("ds-b-1");
        assertThat(source.labels("nope")).isEmpty();
    }

    @Test
    void theCatalogHoldsTheProjectAndTheSelectedDependenciesAtTheirExactVersions() throws IOException {
        OpenSearchEvalDataSource source = new OpenSearchEvalDataSource(client, seededIndices());

        CatalogView catalog = source.catalog(CODE);

        assertThat(catalog.statementsAt("svc", "p.C", 10)).extracting(CatalogEntry::statementId).containsExactly("p1");
        assertThat(catalog.statementsAt("svc", "lib.U", 5)).extracting(CatalogEntry::statementId).containsExactly("d1");
        assertThat(catalog.statementsAt("svc", "lib.V", 7)).isEmpty();
        assertThat(catalog.byId("d2")).isEmpty();
        assertThat(catalog.size()).isEqualTo(2);
    }

    @Test
    void anUnknownCodeVersionIsAUserError() throws IOException {
        OpenSearchEvalDataSource source = new OpenSearchEvalDataSource(client, seededIndices());

        assertThatThrownBy(() -> source.catalog(new CodeVersion("petclinic", "v-missing")))
            .isInstanceOf(EvalUserException.class)
            .hasMessageContaining("analyzer project");
    }

    @Test
    void missingIndicesAreTreatedAsEmptyAndNothingIsCreated() throws IOException {
        IndexNames names = new IndexNames("it-missing-" + UUID.randomUUID() + "-");
        OpenSearchEvalDataSource source = new OpenSearchEvalDataSource(client, names);

        assertThat(source.events("ds")).isEmpty();
        assertThat(source.labels("ds")).isEmpty();
        assertThatThrownBy(() -> source.catalog(CODE)).isInstanceOf(EvalUserException.class);
        assertThat(new IndexManager(client, names).exists(names.logs())).isFalse();
        assertThat(new IndexManager(client, names).exists(names.labels())).isFalse();
    }

    @Test
    void aWholeRunUsesTheOracleAndTheManualLabel() throws IOException {
        OpenSearchEvalDataSource source = new OpenSearchEvalDataSource(client, seededIndices());

        EvalResult result = EvalRunner.run(source, "ds", 25, 42);

        // ds-a-1 (p1) and ds-a-2 (d1) are right; ds-a-3 is unmatched but a manual label says p1 was the answer
        assertThat(result.metrics().counts().totalEvents()).isEqualTo(3);
        assertThat(result.metrics().counts().truthManual()).isEqualTo(1);
        assertThat(result.metrics().counts().truthOracle()).isEqualTo(2);
        assertThat(result.metrics().counts().evaluable()).isEqualTo(3);
        assertThat(result.metrics().headline().accuracyAt1()).isEqualTo(2.0 / 3);
        assertThat(result.errorTypes()).hasSize(1);
        assertThat(result.errorTypes().get(0).event().logId()).isEqualTo("ds-a-3");
        assertThat(result.errorTypes().get(0).event().truthStatementIds()).containsExactly("d1");
    }
}
