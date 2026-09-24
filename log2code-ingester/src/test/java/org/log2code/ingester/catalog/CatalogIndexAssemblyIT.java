package org.log2code.ingester.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.Level;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.TypeInfo;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T19 AC1/IT: {@link CatalogIndex#load} against a real OpenSearch - the {@code AnalysisRun} lookup,
 * the per-code-unit query (project + every service's selected dependencies) and the server-side
 * {@code template_kind = unsupported} exclusion all have to work over the wire, not just in memory.
 */
@Testcontainers
class CatalogIndexAssemblyIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic-it", "v1");
    private static final CodeUnit HIKARI = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "com.zaxxer:HikariCP", "7.0.2");
    private static final String OWNER_RESOURCE = "org.log2code.fixture.customers.OwnerResource";
    private static final String HIKARI_POOL = "com.zaxxer.hikari.pool.HikariPool";

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
    void loadsTheApplicableProjectAndDependencyCatalogFromRealOpenSearch() throws IOException {
        IndexNames names = new IndexNames("it-" + UUID.randomUUID() + "-");
        new IndexManager(client, names).ensureAll();

        AnalysisRun run = new AnalysisRun("run-it-1", CodeUnit.TYPE_PROJECT, PROJECT,
            "https://example.invalid/petclinic-it", "test-analyzer",
            Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:05Z"), 5000, Map.of(),
            List.of(new ModuleInfo("spring-petclinic-customers-service", "customers-service",
                List.of("src/main/java"), List.of(), List.of("com.zaxxer:HikariCP:7.0.2"))));
        client.index(i -> i.index(names.runs()).id(run.runId()).document(run));

        try (BulkWriter<CatalogEntry> catalogWriter =
                 new BulkWriter<>(client, names.catalog(), CatalogEntry::statementId)) {
            catalogWriter.add(entry("stmt-owner", PROJECT, "spring-petclinic-customers-service", "customers-service",
                OWNER_RESOURCE, OWNER_RESOURCE, "class_literal", "Saving owner {}", "placeholders"));
            catalogWriter.add(entry("stmt-hikari", HIKARI, "com.zaxxer:HikariCP", null,
                HIKARI_POOL, HIKARI_POOL, "class_literal", "Added connection {}", "placeholders"));
            // Never applicable: a different, unselected dependency version of the same artifact.
            catalogWriter.add(entry("stmt-hikari-other-version",
                new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "com.zaxxer:HikariCP", "6.0.0"), "com.zaxxer:HikariCP", null,
                HIKARI_POOL, HIKARI_POOL, "class_literal", "Added connection {}", "placeholders"));
            // Excluded server-side: template_kind = unsupported never even reaches this JVM.
            catalogWriter.add(entry("stmt-unsupported", PROJECT, "spring-petclinic-customers-service",
                "customers-service", OWNER_RESOURCE, null, "unknown", "n/a", "unsupported"));
            catalogWriter.flush();
            assertThat(catalogWriter.report().failed()).isZero();
        }
        try (BulkWriter<TypeInfo> typesWriter = new BulkWriter<>(client, names.types(), TypeInfo::typeId)) {
            typesWriter.add(new TypeInfo("type-owner", PROJECT, "spring-petclinic-customers-service",
                "OwnerResource.java", OWNER_RESOURCE, OWNER_RESOURCE, null, List.of(), "class"));
            typesWriter.flush();
            assertThat(typesWriter.report().failed()).isZero();
        }
        IndexManager indexManager = new IndexManager(client, names);
        indexManager.refresh(names.runs());
        indexManager.refresh(names.catalog());
        indexManager.refresh(names.types());

        CatalogIndex index = CatalogIndex.loadFrom(client, names, "petclinic-it", "v1");

        // 2, not 4: stmt-unsupported and stmt-hikari-other-version are both excluded (unsupported
        // server-side via the query; the other HikariCP version because it was never selected).
        assertThat(index.statementCount()).isEqualTo(2);
        assertThat(index.applicableStatementCount("customers-service")).isEqualTo(2);

        LoggerResolver.Resolution resolution = index.loggerResolver().resolve(OWNER_RESOURCE, "customers-service");
        assertThat(resolution.kind()).isEqualTo(LoggerResolver.Kind.EXACT);
        assertThat(index.byLogger(resolution.names(), "customers-service"))
            .extracting(CatalogKey::statementId).containsExactly("stmt-owner");
        assertThat(index.byTokens(List.of("added", "connection"), "customers-service", 10))
            .extracting(CatalogKey::statementId).containsExactly("stmt-hikari");
    }

    @Test
    void loadingAProjectVersionWithNoAnalysisRunFailsWithAClearError() {
        IndexNames names = new IndexNames("it-" + UUID.randomUUID() + "-");

        assertThatThrownBy(() -> CatalogIndex.loadFrom(client, names, "never-analyzed", "v1"))
            .isInstanceOf(CatalogLoadException.class)
            .hasMessageContaining("never-analyzed")
            .hasMessageContaining("analyzer project");
    }

    private static CatalogEntry entry(String statementId, CodeUnit codeUnit, String module, String service,
                                       String classFqn, String loggerName, String loggerNameKind,
                                       String template, String templateKind) {
        return new CatalogEntry(
            statementId, statementId + "-logical", codeUnit, module, service,
            "Fixture.java", "file-" + statementId, "pkg", classFqn, classFqn,
            "run", "run()", null, false,
            10, 10, 4, 5, 15,
            "slf4j", "typed", "log", loggerName, loggerNameKind,
            Level.INFO, false,
            "\"" + template + "\"", template, templateKind, null, null, List.of(), template.length(), 0, false,
            new EnclosingBlock("method", null, null, 5, 15), null,
            "    log.info(...);\n", 10,
            null, "test-analyzer", Instant.parse("2026-09-24T10:00:00Z"));
    }
}
