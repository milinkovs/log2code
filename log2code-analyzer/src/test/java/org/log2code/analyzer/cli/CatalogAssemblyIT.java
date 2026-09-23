package org.log2code.analyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.catalog.ProjectCatalogBuilder;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T10 AC5: against the {@code fixtures/mini-project} mini project, {@code catalog}, {@code sources},
 * {@code types} and {@code runs} are populated per the 0.7 schema through a real OpenSearch, and a
 * second write for the same {@code code_unit.version} is idempotent (T10 step 4).
 */
@Testcontainers
class CatalogAssemblyIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

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
    void writesCatalogSourcesTypesAndRunPerSchema() throws IOException, URISyntaxException {
        IndexNames names = freshIndexNames();
        Path projectRoot = fixtureRoot();
        List<ModuleInfo> modules = new ModuleScanner().scan(projectRoot, List.of(), List.of());
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, "mini-project-it", "v1");
        Instant analyzedAt = Instant.parse("2026-09-23T10:00:00.000Z");

        ProjectCatalogBuilder.Result result = ProjectCatalogBuilder.build(projectRoot, modules, codeUnit, 3, 10, "test-analyzer", analyzedAt);
        CatalogWriter.writeToOpenSearch(client, names, codeUnit, result.catalog(), result.sources(), result.types(), List.of());

        AnalysisRun run = new AnalysisRun(
            StableIds.runId(CodeUnit.TYPE_PROJECT, codeUnit.name(), codeUnit.version(), "test-analyzer"),
            CodeUnit.TYPE_PROJECT, codeUnit, "https://example.invalid/mini-project", "test-analyzer",
            analyzedAt, analyzedAt.plus(Duration.ofSeconds(1)), 1000, result.stats(), modules);
        RunWriter.writeToOpenSearch(client, names, run);

        IndexManager indexManager = new IndexManager(client, names);
        indexManager.refresh(names.catalog());
        indexManager.refresh(names.sources());
        indexManager.refresh(names.types());

        assertThat(indexManager.count(names.catalog(), Map.of())).isEqualTo(10);
        assertThat(indexManager.count(names.sources(), Map.of())).isEqualTo(7);
        assertThat(indexManager.count(names.types(), Map.of())).isEqualTo(7);

        DocumentReader reader = new DocumentReader(client);
        CatalogEntry expected = result.catalog().get(0);
        CatalogEntry fetched = reader.get(names.catalog(), expected.statementId(), CatalogEntry.class);
        assertThat(fetched).isEqualTo(expected);

        AnalysisRun fetchedRun = reader.get(names.runs(), run.runId(), AnalysisRun.class);
        assertThat(fetchedRun.stats()).containsEntry("statement_count", 10);
        assertThat(fetchedRun.codeUnit()).isEqualTo(codeUnit);
    }

    @Test
    void secondWriteForTheSameVersionReplacesRatherThanAccumulates() throws IOException, URISyntaxException {
        IndexNames names = freshIndexNames();
        Path projectRoot = fixtureRoot();
        List<ModuleInfo> modules = new ModuleScanner().scan(projectRoot, List.of(), List.of());
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, "mini-project-it", "v1");
        Instant analyzedAt = Instant.parse("2026-09-23T10:00:00.000Z");
        ProjectCatalogBuilder.Result full = ProjectCatalogBuilder.build(projectRoot, modules, codeUnit, 3, 10, "test-analyzer", analyzedAt);

        CatalogWriter.writeToOpenSearch(client, names, codeUnit, full.catalog(), full.sources(), full.types(), List.of());
        IndexManager indexManager = new IndexManager(client, names);
        // Refresh all three before the second write's delete-by-query runs: delete-by-query only finds
        // already-searchable (refreshed) documents, and this first write's documents are not yet visible
        // by default (OpenSearch's near-real-time search).
        indexManager.refresh(names.catalog());
        indexManager.refresh(names.sources());
        indexManager.refresh(names.types());
        assertThat(indexManager.count(names.catalog(), Map.of())).isEqualTo(full.catalog().size());

        // Re-analyzing module-b only (same code_unit.version) must delete module-a's stale documents
        // first (T10 step 4), not just add module-b's on top of the previous full write.
        List<ModuleInfo> moduleBOnly = modules.stream().filter(m -> m.module().equals("module-b")).toList();
        ProjectCatalogBuilder.Result partial = ProjectCatalogBuilder.build(projectRoot, moduleBOnly, codeUnit, 3, 10, "test-analyzer", analyzedAt);
        CatalogWriter.writeToOpenSearch(client, names, codeUnit, partial.catalog(), partial.sources(), partial.types(), List.of());
        indexManager.refresh(names.catalog());
        indexManager.refresh(names.sources());
        indexManager.refresh(names.types());

        assertThat(indexManager.count(names.catalog(), Map.of())).isEqualTo(partial.catalog().size());
        assertThat(indexManager.count(names.sources(), Map.of())).isEqualTo(partial.sources().size());
        assertThat(indexManager.count(names.types(), Map.of())).isEqualTo(partial.types().size());
    }

    private static IndexNames freshIndexNames() {
        return new IndexNames("it-" + UUID.randomUUID() + "-");
    }

    private static Path fixtureRoot() throws URISyntaxException {
        return Paths.get(CatalogAssemblyIT.class.getResource("/fixtures/mini-project/pom.xml").toURI()).getParent();
    }
}
