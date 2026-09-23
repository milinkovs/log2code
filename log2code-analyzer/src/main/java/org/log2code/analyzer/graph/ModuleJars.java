package org.log2code.analyzer.graph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.log2code.analyzer.deps.DepsManifest;
import org.log2code.core.json.Json;

/**
 * Reads {@code data/work/deps/deps-manifest.json} (T12) for the call graph's type solver (T13 step 1):
 * every dependency jar listed for a module, not just the ones {@code deps resolve} marked {@code
 * selected} (that flag drives which libraries get their <em>sources</em> analyzed for the catalog, T14
 * - an unrelated concern from resolving calls made against a library's compiled classes, which needs
 * every jar on the module's real classpath to reach a useful resolution rate, ADR-013). Missing/empty
 * when {@code deps resolve} has not run yet, so {@link org.log2code.analyzer.cli.ProjectCommand} can
 * skip the graph gracefully rather than fail a plain catalog build.
 */
public final class ModuleJars {

    private ModuleJars() {
    }

    /** {@code module name -> every dependency jar path that exists on disk}, or empty if the manifest is missing. */
    public static Optional<Map<String, List<Path>>> load(Path manifestFile) {
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        DepsManifest manifest;
        try {
            manifest = Json.mapper().readValue(manifestFile.toFile(), DepsManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + manifestFile, e);
        }
        Map<String, List<Path>> byModule = new LinkedHashMap<>();
        for (DepsManifest.Module module : manifest.modules()) {
            List<Path> jars = module.artifacts().stream()
                .map(DepsManifest.ManifestArtifact::jar)
                .filter(java.util.Objects::nonNull)
                .map(Path::of)
                .distinct()
                .filter(Files::isRegularFile)
                .toList();
            byModule.put(module.module(), jars);
        }
        return Optional.of(byModule);
    }
}
