package org.log2code.analyzer.deps;

import java.nio.file.Files;
import java.nio.file.Path;

/** Downloads one selected artifact's {@code -sources.jar} (T12 step 3) into the local {@code ~/.m2} repository. */
public final class SourcesFetcher {

    private SourcesFetcher() {
    }

    public record Result(Path sourcesJar, boolean missing) {
    }

    public static Result fetch(MavenCli maven, Artifact artifact) {
        boolean ok = maven.fetchSources(artifact.gav());
        if (ok) {
            Path sourcesJar = artifact.jarPath().getParent()
                .resolve(artifact.artifactId() + "-" + artifact.version() + "-sources.jar");
            if (Files.isRegularFile(sourcesJar)) {
                return new Result(sourcesJar, false);
            }
        }
        return new Result(null, true);
    }
}
