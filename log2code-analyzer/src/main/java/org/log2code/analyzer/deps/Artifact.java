package org.log2code.analyzer.deps;

import java.nio.file.Path;

/** One resolved runtime dependency of a module (T12 step 1, {@code mvn dependency:list} output). */
public record Artifact(String groupId, String artifactId, String version, String type, String scope, Path jarPath) {

    public String gav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    public String groupArtifact() {
        return groupId + ":" + artifactId;
    }
}
