package org.log2code.analyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.catalog.ProjectCatalogBuilder;
import org.log2code.analyzer.graph.CatalogGraphEnricher;
import org.log2code.analyzer.graph.ProjectMethodGraphBuilder;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.MethodInfo;
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
 * T13 AC: against {@code fixtures/mini-project} (T10's own fixture, which already exercises inheritance,
 * an anonymous class and a caught exception), the call graph is written to {@code log2code-methods} and
 * the catalog written alongside it carries the enriched {@code method_id}/{@code control.calls_before}.
 * No dependency jars are supplied - every call in this fixture targets a project method, the JDK or
 * SLF4J's {@code Logger}, all resolvable via {@code ReflectionTypeSolver(jreOnly=true)} plus the
 * project's own sources, except the SLF4J calls themselves (no SLF4J jar here), which is exactly the
 * "external, unresolved" case T13 step 2 has to tolerate.
 */
@Testcontainers
class MethodGraphAssemblyIT {

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
    void writesMethodsAndEnrichesTheCatalogWithMethodIdAndResolvedCallsBefore() throws IOException, URISyntaxException {
        IndexNames names = new IndexNames("it-" + UUID.randomUUID() + "-");
        Path projectRoot = fixtureRoot();
        List<ModuleInfo> modules = new ModuleScanner().scan(projectRoot, List.of(), List.of());
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, "mini-project-graph-it", "v1");
        Instant analyzedAt = Instant.parse("2026-09-23T10:00:00.000Z");

        ProjectCatalogBuilder.Result catalogResult =
            ProjectCatalogBuilder.build(projectRoot, modules, codeUnit, 3, 10, "test-analyzer", analyzedAt);
        ProjectMethodGraphBuilder.Result graphResult =
            ProjectMethodGraphBuilder.build(projectRoot, modules, codeUnit, Map.of());
        List<CatalogEntry> enrichedCatalog = CatalogGraphEnricher.enrich(catalogResult.catalog(), graphResult.methods());

        CatalogWriter.writeToOpenSearch(client, names, codeUnit, enrichedCatalog, catalogResult.sources(),
            catalogResult.types(), graphResult.methods());

        IndexManager indexManager = new IndexManager(client, names);
        indexManager.refresh(names.catalog());
        indexManager.refresh(names.methods());

        assertThat(indexManager.count(names.methods(), Map.of())).isEqualTo(graphResult.methods().size());
        assertThat(graphResult.methods()).isNotEmpty();

        DocumentReader reader = new DocumentReader(client);
        for (CatalogEntry entry : enrichedCatalog) {
            assertThat(entry.methodId())
                .as("catalog.method_id for %s#%s", entry.classFqn(), entry.methodSignature())
                .isNotNull();
            CatalogEntry fetched = reader.get(names.catalog(), entry.statementId(), CatalogEntry.class);
            assertThat(fetched.methodId()).isEqualTo(entry.methodId());
        }

        // At least one MethodInfo's calls[] actually resolved to another project method (ADR-013's
        // "resolved_to_project_pct" stat is not 0 for this fixture, which does call between its own
        // methods, e.g. ServiceA.process() calling its own private risky()).
        boolean anyProjectTargetedCall = graphResult.methods().stream()
            .flatMap(m -> m.calls().stream())
            .anyMatch(c -> c.targetMethodId() != null);
        assertThat(anyProjectTargetedCall).isTrue();

        MethodInfo risky = graphResult.methods().stream()
            .filter(m -> m.classFqn().endsWith(".ServiceA") && "risky()".equals(m.methodSignature()))
            .findFirst()
            .orElseThrow();
        assertThat(risky.calledBy()).isNotEmpty(); // ServiceA.process() calls its own private risky()
    }

    private static Path fixtureRoot() throws URISyntaxException {
        return Paths.get(MethodGraphAssemblyIT.class.getResource("/fixtures/mini-project/pom.xml").toURI()).getParent();
    }
}
