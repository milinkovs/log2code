package org.log2code.analyzer.deps;

import java.util.List;

/**
 * {@code data/work/deps/deps-manifest.json} (T12 step 4). Field names are camelCase here; {@code Json.mapper()}
 * (core, T04) serializes records via {@code SNAKE_CASE}, giving exactly the 0.11-shaped output
 * ({@code project_commit}, {@code sources_jar}, {@code logger_hits}, {@code sources_missing}).
 */
public record DepsManifest(String projectCommit, List<Module> modules) {

    public record Module(String module, String service, List<ManifestArtifact> artifacts) {
    }

    public record ManifestArtifact(String gav, String jar, String sourcesJar, boolean selected, String reason,
                                    int loggerHits, boolean sourcesMissing) {
    }
}
