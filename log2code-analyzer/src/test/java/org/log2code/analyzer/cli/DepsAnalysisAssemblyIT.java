package org.log2code.analyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.analyzer.deps.DepsManifest;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T14 AC1/AC4/AC5, against a real OpenSearch: {@code deps analyze}'s orchestration
 * ({@link DepsAnalyzeRunner}, exercised directly - CLI options and the hardcoded
 * {@code data/work/deps/deps-manifest.json} path belong to {@link DepsAnalyzeCommand} alone, same split
 * as {@code ProjectCommand}/{@code CatalogAssemblyIT}) writes {@code catalog}/{@code sources}/
 * {@code types}/{@code runs} per selected artifact, a second run without {@code --force} skips
 * already-analyzed artifacts (step 5) while {@code --force} re-analyzes them, and the markdown report
 * (step 7) always reflects every selected artifact, skipped or not.
 */
@Testcontainers
class DepsAnalysisAssemblyIT {

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
    void writesCatalogSourcesTypesAndRunForEverySelectedArtifact(@TempDir Path dir) throws IOException {
        Path jar = writeSourcesJar(dir, "lib-a");
        DepsManifest manifest = manifestOf(jar, "com.example:lib-a:1.0.0");
        IndexNames names = freshIndexNames();

        DepsAnalyzeRunner.Summary summary = DepsAnalyzeRunner.run(client, names, OutputMode.OPENSEARCH, dir.resolve("json"),
            dir.resolve("deps-report.md"), manifest, false, null, "test-analyzer", 3, 10);

        assertThat(summary.outcomes()).hasSize(1);
        assertThat(summary.outcomes().get(0).skipped()).isFalse();
        assertThat(summary.totalStatements()).isEqualTo(1);

        IndexManager indexManager = new IndexManager(client, names);
        indexManager.refresh(names.catalog());
        indexManager.refresh(names.sources());
        indexManager.refresh(names.types());
        indexManager.refresh(names.runs());

        assertThat(indexManager.count(names.catalog(), Map.of())).isEqualTo(1);
        assertThat(indexManager.count(names.sources(), Map.of())).isEqualTo(1);
        assertThat(indexManager.count(names.types(), Map.of())).isEqualTo(3);
        assertThat(indexManager.count(names.runs(), Map.of())).isEqualTo(1);
        assertThat(indexManager.count(names.catalog(), Map.of("code_unit.name", "com.example:lib-a"))).isEqualTo(1);

        assertThat(Files.readString(dir.resolve("deps-report.md")))
            .contains("com.example:lib-a:1.0.0")
            .contains("| **Ukupno** | 3 | 1 |");
    }

    @Test
    void secondRunWithoutForceSkipsButForceReanalyzes(@TempDir Path dir) throws IOException {
        Path jar = writeSourcesJar(dir, "lib-b");
        DepsManifest manifest = manifestOf(jar, "com.example:lib-b:2.0.0");
        IndexNames names = freshIndexNames();
        IndexManager indexManager = new IndexManager(client, names);

        DepsAnalyzeRunner.run(client, names, OutputMode.OPENSEARCH, dir.resolve("json"),
            dir.resolve("report1.md"), manifest, false, null, "test-analyzer", 3, 10);
        // The idempotency check (step 5) is a GET by id, which OpenSearch serves in real time (unlike
        // search), so no refresh is needed here before the second run's existence check.

        DepsAnalyzeRunner.Summary secondRun = DepsAnalyzeRunner.run(client, names, OutputMode.OPENSEARCH, dir.resolve("json"),
            dir.resolve("report2.md"), manifest, false, null, "test-analyzer", 3, 10);
        assertThat(secondRun.outcomes()).hasSize(1);
        assertThat(secondRun.outcomes().get(0).skipped()).isTrue();
        // AC1's "total statements" must still reflect the whole catalog on a mostly-skipped run.
        assertThat(secondRun.totalStatements()).isEqualTo(1);
        assertThat(Files.readString(dir.resolve("report2.md"))).contains("com.example:lib-b:2.0.0 *(preskočeno)*");

        indexManager.refresh(names.catalog());
        assertThat(indexManager.count(names.catalog(), Map.of())).isEqualTo(1);

        DepsAnalyzeRunner.Summary forcedRun = DepsAnalyzeRunner.run(client, names, OutputMode.OPENSEARCH, dir.resolve("json"),
            dir.resolve("report3.md"), manifest, true, null, "test-analyzer", 3, 10);
        assertThat(forcedRun.outcomes().get(0).skipped()).isFalse();

        indexManager.refresh(names.catalog());
        assertThat(indexManager.count(names.catalog(), Map.of())).isEqualTo(1);
    }

    @Test
    void artifactFilterOnlyAnalyzesTheMatchingSelectedArtifact(@TempDir Path dir) throws IOException {
        Path jarA = writeSourcesJar(dir.resolve("a"), "lib-a");
        Path jarB = writeSourcesJar(dir.resolve("b"), "lib-b");
        DepsManifest manifest = manifestOf(
            Map.of("com.example:lib-a:1.0.0", jarA, "com.example:lib-b:2.0.0", jarB));
        IndexNames names = freshIndexNames();

        DepsAnalyzeRunner.Summary summary = DepsAnalyzeRunner.run(client, names, OutputMode.OPENSEARCH, dir.resolve("json"),
            dir.resolve("deps-report.md"), manifest, false, "com.example:lib-b:2.0.0", "test-analyzer", 3, 10);

        assertThat(summary.outcomes()).extracting(DepsAnalyzeRunner.ArtifactOutcome::gav)
            .containsExactly("com.example:lib-b:2.0.0");
    }

    private static IndexNames freshIndexNames() {
        return new IndexNames("it-" + UUID.randomUUID() + "-");
    }

    private static DepsManifest manifestOf(Path sourcesJar, String gav) {
        return manifestOf(Map.of(gav, sourcesJar));
    }

    private static DepsManifest manifestOf(Map<String, Path> gavToSourcesJar) {
        List<DepsManifest.ManifestArtifact> artifacts = gavToSourcesJar.entrySet().stream()
            .map(e -> new DepsManifest.ManifestArtifact(e.getKey(), "/fake/binary.jar", e.getValue().toString(),
                true, "seen-in-logs", 1, false))
            .toList();
        DepsManifest.Module module = new DepsManifest.Module("spring-petclinic-customers-service", "customers-service", artifacts);
        return new DepsManifest("deadbeef", List.of(module));
    }

    /** Same 3-file fixture as {@code DependencyCatalogBuilderTest}: one class logs, two others do not. */
    private static Path writeSourcesJar(Path dir, String libName) throws IOException {
        Files.createDirectories(dir);
        // libName (e.g. "lib-a") is used in the jar's own file name and in the gav below, but a Java
        // package name cannot contain '-', so the fixture's package uses a hyphen-free variant of it.
        String javaName = libName.replace("-", "");
        String pkg = "com/example/" + javaName;
        Map<String, String> entries = Map.of(
            pkg + "/LoggingBase.java", """
                package com.example.%s;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public abstract class LoggingBase {
                    protected static final Logger log = LoggerFactory.getLogger(LoggingBase.class);
                }
                """.formatted(javaName),
            pkg + "/ServiceA.java", """
                package com.example.%s;

                public class ServiceA extends LoggingBase {
                    public void run() {
                        log.info("Handling {}", "x");
                    }
                }
                """.formatted(javaName),
            pkg + "/NoLogging.java", """
                package com.example.%s;

                public class NoLogging {
                    public void doNothing() {
                    }
                }
                """.formatted(javaName)
        );

        Path jar = dir.resolve(libName + "-sources.jar");
        try (OutputStream fileOut = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jarOut.putNextEntry(new JarEntry(entry.getKey()));
                jarOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        }
        return jar;
    }
}
