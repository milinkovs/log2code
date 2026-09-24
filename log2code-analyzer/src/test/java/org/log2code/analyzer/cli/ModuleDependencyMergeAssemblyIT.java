package org.log2code.analyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.AnalysisRun;
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
 * ADR-020: reproduces, against a real OpenSearch, the exact read-then-merge sequence
 * {@code ProjectCommand.readExistingRun}/{@link ModuleDependencyMerger} run before every {@code project}
 * write - the fix for the gap ADR-013 documented, where a plain {@code project} re-run after
 * {@code deps resolve} silently wiped {@code modules[].selectedDependencies} back to empty.
 */
@Testcontainers
class ModuleDependencyMergeAssemblyIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "merge-it", "v1");
    private static final Instant T0 = Instant.parse("2026-09-24T10:00:00Z");

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
    void reReadingBeforeAFreshProjectRunCarriesOverSelectedDependenciesFromDepsResolve() throws IOException {
        IndexNames names = freshIndexNames();
        String runId = StableIds.runId(CodeUnit.TYPE_PROJECT, CODE_UNIT.name(), CODE_UNIT.version(), "test-analyzer");

        // Simulates 'deps resolve' (T12): the run doc already carries resolved/selected dependencies.
        List<ModuleInfo> afterDepsResolve = List.of(
            new ModuleInfo("mod-a", "svc-a", List.of("src/main/java"),
                List.of("g:a:1.0", "g:b:2.0"), List.of("g:a:1.0")),
            new ModuleInfo("mod-b", "svc-b", List.of("src/main/java"), List.of(), List.of()));
        RunWriter.writeToOpenSearch(client, names, run(runId, afterDepsResolve));
        new IndexManager(client, names).refresh(names.runs());

        // Simulates a fresh 'analyzer project' re-scan: ModuleScanner knows nothing about dependencies.
        List<ModuleInfo> freshlyScanned = List.of(
            new ModuleInfo("mod-a", "svc-a", List.of("src/main/java"), List.of(), List.of()),
            new ModuleInfo("mod-b", "svc-b", List.of("src/main/java"), List.of(), List.of()));

        AnalysisRun existing = readExisting(names, runId);
        List<ModuleInfo> merged = ModuleDependencyMerger.merge(freshlyScanned, existing);

        ModuleInfo mergedA = merged.stream().filter(m -> m.module().equals("mod-a")).findFirst().orElseThrow();
        assertThat(mergedA.dependencies()).containsExactly("g:a:1.0", "g:b:2.0");
        assertThat(mergedA.selectedDependencies()).containsExactly("g:a:1.0");
        ModuleInfo mergedB = merged.stream().filter(m -> m.module().equals("mod-b")).findFirst().orElseThrow();
        assertThat(mergedB.selectedDependencies()).isEmpty();

        // Writing the merged run back (what ProjectCommand does next) must not lose it again.
        RunWriter.writeToOpenSearch(client, names, run(runId, merged));
        new IndexManager(client, names).refresh(names.runs());
        AnalysisRun roundTripped = readExisting(names, runId);
        assertThat(roundTripped.modules()).extracting(ModuleInfo::module, ModuleInfo::selectedDependencies)
            .containsExactlyInAnyOrder(
                tuple("mod-a", List.of("g:a:1.0")),
                tuple("mod-b", List.of()));
    }

    @Test
    void readingBeforeTheVeryFirstProjectRunFindsNothingWithoutThrowing() throws IOException {
        IndexNames names = freshIndexNames();
        String runId = StableIds.runId(CodeUnit.TYPE_PROJECT, "never-analyzed", "v1", "test-analyzer");

        // No prior write at all for this runId - not even the indices exist yet (a brand new
        // deployment). Reading must tolerate this (ProjectCommand.readExistingRun calls
        // IndexManager.ensureAll() first), not throw an "index not found" error.
        AnalysisRun existing = readExisting(names, runId);

        assertThat(existing).isNull();
        List<ModuleInfo> fresh = List.of(new ModuleInfo("mod-a", "svc-a", List.of("src/main/java"), List.of(), List.of()));
        assertThat(ModuleDependencyMerger.merge(fresh, existing)).isEqualTo(fresh);
    }

    private static AnalysisRun readExisting(IndexNames names, String runId) throws IOException {
        new IndexManager(client, names).ensureAll();
        return new DocumentReader(client).get(names.runs(), runId, AnalysisRun.class);
    }

    private static AnalysisRun run(String runId, List<ModuleInfo> modules) {
        return new AnalysisRun(runId, CodeUnit.TYPE_PROJECT, CODE_UNIT, "https://example.invalid/merge-it",
            "test-analyzer", T0, T0.plus(Duration.ofSeconds(1)), 1000, Map.of(), modules);
    }

    private static IndexNames freshIndexNames() {
        return new IndexNames("it-" + UUID.randomUUID() + "-");
    }
}
