package org.log2code.analyzer.cli;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.log2code.analyzer.CliUserException;
import org.log2code.analyzer.catalog.DependencyCatalogBuilder;
import org.log2code.analyzer.deps.DepsManifest;
import org.log2code.analyzer.deps.Gav;
import org.log2code.core.ids.StableIds;
import org.log2code.core.json.Json;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Orchestrates {@code deps analyze} (T14): reads the already-parsed {@link DepsManifest}, takes every
 * distinct (by g:a:v) {@code selected} artifact, and for each one either skips it (step 5: an
 * {@link AnalysisRun} for the same code unit and analyzer version already exists, no {@code --force})
 * or builds and writes its catalog ({@link DependencyCatalogBuilder} + {@link CatalogWriter}/
 * {@link RunWriter}). Free of CLI/picocli concerns and hardcoded paths (unlike {@link DepsAnalyzeCommand},
 * its only caller) so it can be exercised directly by tests, the same split {@code ProjectCatalogBuilder}
 * and {@code CatalogWriter} already use elsewhere.
 */
final class DepsAnalyzeRunner {

    private DepsAnalyzeRunner() {
    }

    /** One artifact's outcome: {@code skipped} mirrors {@link DepsReport.Row#skipped()}. */
    record ArtifactOutcome(String gav, boolean skipped, boolean sourcesMissing) {
    }

    record Summary(List<DepsReport.Row> rows, List<ArtifactOutcome> outcomes, long maxHeapBytes) {

        long totalStatements() {
            return rows.stream().mapToLong(DepsReport.Row::statementCount).sum();
        }
    }

    static Summary run(OpenSearchClient client, IndexNames indexNames, OutputMode out, Path jsonDir,
                        Path reportTarget, DepsManifest manifest, boolean force, String artifactFilter,
                        String analyzerVersion, int snippetLines, int maxPrecedingStatements) throws IOException {
        if (out.writesToOpenSearch()) {
            new IndexManager(client, indexNames).ensureAll();
        }

        List<DepsManifest.ManifestArtifact> selected = selectedArtifacts(manifest, artifactFilter);

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long maxHeapUsed = memoryBean.getHeapMemoryUsage().getUsed();

        List<DepsReport.Row> rows = new ArrayList<>();
        List<ArtifactOutcome> outcomes = new ArrayList<>();

        for (DepsManifest.ManifestArtifact artifact : selected) {
            if (artifact.sourcesJar() == null) {
                System.err.println("skip " + artifact.gav() + ": no sources jar (sources_missing)");
                outcomes.add(new ArtifactOutcome(artifact.gav(), false, true));
                continue;
            }

            Gav gav = Gav.parse(artifact.gav());
            CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, gav.groupArtifact(), gav.version());
            String runId = StableIds.runId(CodeUnit.TYPE_DEPENDENCY, codeUnit.name(), codeUnit.version(), analyzerVersion);

            if (!force) {
                AnalysisRun existing = findExistingRun(client, indexNames, out, jsonDir, codeUnit, runId);
                if (existing != null) {
                    System.out.println("skip " + artifact.gav() + ": already analyzed (run " + runId + ")");
                    rows.add(DepsReport.Row.from(artifact.gav(), existing.stats(), true));
                    outcomes.add(new ArtifactOutcome(artifact.gav(), true, false));
                    continue;
                }
            }

            Path jarPath = Path.of(artifact.sourcesJar());
            if (!Files.isRegularFile(jarPath)) {
                System.err.println("skip " + artifact.gav() + ": sources jar not found on disk: " + jarPath);
                outcomes.add(new ArtifactOutcome(artifact.gav(), false, true));
                continue;
            }

            Instant startedAt = Instant.now();
            DependencyCatalogBuilder.Result result = DependencyCatalogBuilder.build(
                jarPath, codeUnit, snippetLines, maxPrecedingStatements, analyzerVersion, startedAt);
            Instant finishedAt = Instant.now();
            maxHeapUsed = Math.max(maxHeapUsed, memoryBean.getHeapMemoryUsage().getUsed());

            AnalysisRun run = new AnalysisRun(runId, CodeUnit.TYPE_DEPENDENCY, codeUnit, null, analyzerVersion,
                startedAt, finishedAt, Duration.between(startedAt, finishedAt).toMillis(), result.stats(), null);

            if (out.writesToOpenSearch()) {
                CatalogWriter.writeToOpenSearch(client, indexNames, codeUnit, result.catalog(), result.sources(),
                    result.types(), List.of());
                RunWriter.writeToOpenSearch(client, indexNames, run);
            }
            if (out.writesToJson()) {
                CatalogWriter.writeToJson(jsonDir, codeUnit, result.catalog(), result.sources(), result.types(), List.of());
                RunWriter.writeToJson(jsonDir, run);
            }

            System.out.printf("%s: %d files, %d statements (%d ms)%n", artifact.gav(),
                result.stats().get("file_count"), result.catalog().size(), run.durationMs());
            if (!result.parseFailures().isEmpty()) {
                System.err.println("  " + result.parseFailures().size() + " file(s) failed to parse in " + artifact.gav());
            }

            rows.add(DepsReport.Row.from(artifact.gav(), result.stats(), false));
            outcomes.add(new ArtifactOutcome(artifact.gav(), false, false));
        }

        DepsReport.write(reportTarget, rows);
        return new Summary(List.copyOf(rows), List.copyOf(outcomes), maxHeapUsed);
    }

    /** Step 1: every {@code selected: true} artifact, deduplicated by g:a:v, sorted for a deterministic run. */
    private static List<DepsManifest.ManifestArtifact> selectedArtifacts(DepsManifest manifest, String artifactFilter) {
        Map<String, DepsManifest.ManifestArtifact> distinctByGav = new LinkedHashMap<>();
        for (DepsManifest.Module module : manifest.modules()) {
            for (DepsManifest.ManifestArtifact artifact : module.artifacts()) {
                if (artifact.selected()) {
                    distinctByGav.putIfAbsent(artifact.gav(), artifact);
                }
            }
        }
        if (artifactFilter != null) {
            DepsManifest.ManifestArtifact match = distinctByGav.get(artifactFilter);
            if (match == null) {
                throw new CliUserException("--artifact " + artifactFilter
                    + " is not a selected dependency in the manifest (deps-manifest.json)");
            }
            return List.of(match);
        }
        return distinctByGav.values().stream()
            .sorted(Comparator.comparing(DepsManifest.ManifestArtifact::gav))
            .toList();
    }

    /** Step 5: an existing run for this exact {@code runId} (same code unit + analyzer version), or {@code null}. */
    private static AnalysisRun findExistingRun(OpenSearchClient client, IndexNames indexNames, OutputMode out,
                                                Path jsonDir, CodeUnit codeUnit, String runId) throws IOException {
        if (out.writesToOpenSearch()) {
            return new DocumentReader(client).get(indexNames.runs(), runId, AnalysisRun.class);
        }
        if (out.writesToJson()) {
            Path runFile = JsonPaths.forCodeUnit(jsonDir, codeUnit).resolve("run.json");
            if (!Files.isRegularFile(runFile)) {
                return null;
            }
            return Json.mapper().readValue(runFile.toFile(), AnalysisRun.class);
        }
        return null;
    }
}
