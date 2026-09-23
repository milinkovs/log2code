package org.log2code.analyzer.deps;

/** A parsed {@code groupId:artifactId:version} coordinate, as {@link DepsManifest.ManifestArtifact#gav()} stores it. */
public record Gav(String groupId, String artifactId, String version) {

    public String groupArtifact() {
        return groupId + ":" + artifactId;
    }

    public static Gav parse(String gav) {
        String[] parts = gav.split(":", 3);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("not a groupId:artifactId:version coordinate: " + gav);
        }
        return new Gav(parts[0], parts[1], parts[2]);
    }
}
