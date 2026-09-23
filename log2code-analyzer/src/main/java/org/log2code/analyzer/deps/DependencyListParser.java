package org.log2code.analyzer.deps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the output of {@code mvn dependency:list -DoutputAbsoluteArtifactFilename=true} (T12 step 1).
 *
 * <p>Real output (captured from the PetClinic build) has two quirks not mentioned in 0.11: every line is
 * CRLF-terminated, and Maven appends ANSI colour escapes plus a {@code -- module <name> [auto]}/{@code (auto)}
 * suffix after the jar path (module descriptor info), even when the output goes to a file, not a terminal.
 * Both are stripped here so the parser only has to deal with plain {@code G:A:P[:C]:V:S:<absolute path>.jar}.
 */
public final class DependencyListParser {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-9;]*m");
    // Group 1: groupId:artifactId:type[:classifier]:version:scope. Group 2: the absolute jar path, lazily
    // matched up to the first ".jar" so the trailing "-- module ..." text (already ANSI-stripped) is ignored.
    private static final Pattern ARTIFACT_LINE = Pattern.compile("^(.+):([A-Za-z]:[\\\\/].+?\\.jar)");

    private DependencyListParser() {
    }

    public static List<Artifact> parse(String output) {
        List<Artifact> artifacts = new ArrayList<>();
        String stripped = ANSI_ESCAPE.matcher(output).replaceAll("");
        for (String rawLine : stripped.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = ARTIFACT_LINE.matcher(line);
            if (!matcher.find()) {
                continue; // header line ("The following files have been resolved:") or similar
            }
            Artifact artifact = toArtifact(matcher.group(1), matcher.group(2));
            if (artifact != null) {
                artifacts.add(artifact);
            }
        }
        return artifacts;
    }

    private static Artifact toArtifact(String gavps, String jarPath) {
        String[] parts = gavps.split(":");
        // Standard form has 5 fields (group:artifact:type:version:scope); a classifier adds one more
        // (group:artifact:type:classifier:version:scope). Either way, type/version/scope are always the
        // last 3 before the path; the classifier (if present) is not part of the Artifact record (T12 step 1).
        if (parts.length < 5) {
            return null;
        }
        String groupId = parts[0];
        String artifactId = parts[1];
        String type = parts[2];
        String version = parts[parts.length - 2];
        String scope = parts[parts.length - 1];
        return new Artifact(groupId, artifactId, version, type, scope, Path.of(jarPath.trim()));
    }
}
