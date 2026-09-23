package org.log2code.core.github;

import java.util.regex.Pattern;
import org.log2code.core.model.CodeUnit;

/**
 * Builds a GitHub blob URL for one catalog statement (T15): {@code {repo}/blob/{ref}/{path}#L{line}},
 * or {@code #L{line}-L{end_line}} when the statement spans more than one line. Mappings come from
 * {@code config/code-units.yml} ({@link CodeUnitsConfig}). Returns {@code null} when no mapping
 * applies to the given {@link CodeUnit}, or when the resolved repo is unknown.
 */
public final class GithubLinker {

    private final CodeUnitsConfig config;
    private final String projectRemoteUrl;

    /**
     * @param config           parsed {@code config/code-units.yml}
     * @param projectRemoteUrl fallback {@code repo} for {@code code_unit.type = project} when its
     *                         mapping does not set one (T15 step 2); {@code null} if unavailable
     */
    public GithubLinker(CodeUnitsConfig config, String projectRemoteUrl) {
        this.config = config;
        this.projectRemoteUrl = projectRemoteUrl;
    }

    public String link(CodeUnit codeUnit, String filePath, int line, int endLine) {
        String repo;
        String ref;
        String path;

        if (CodeUnit.TYPE_PROJECT.equals(codeUnit.type())) {
            CodeUnitsConfig.ProjectMapping mapping = config.project().get(codeUnit.name());
            if (mapping == null) {
                return null;
            }
            repo = mapping.repo() != null ? mapping.repo() : projectRemoteUrl;
            ref = substitute(mapping.ref(), codeUnit.version(), filePath, null);
            path = substitute(mapping.path(), codeUnit.version(), filePath, null);
        } else {
            CodeUnitsConfig.DependencyMapping mapping = firstMatch(codeUnit.name());
            if (mapping == null) {
                return null;
            }
            String artifactId = artifactIdOf(codeUnit.name());
            repo = mapping.repo();
            ref = substitute(mapping.ref(), codeUnit.version(), filePath, artifactId);
            path = substitute(mapping.path(), codeUnit.version(), filePath, artifactId);
        }

        if (repo == null || repo.isBlank() || ref == null || path == null) {
            return null;
        }
        String anchor = line == endLine ? "L" + line : "L" + line + "-L" + endLine;
        return repo + "/blob/" + ref + "/" + path + "#" + anchor;
    }

    private CodeUnitsConfig.DependencyMapping firstMatch(String groupArtifact) {
        for (CodeUnitsConfig.DependencyMapping mapping : config.dependencies()) {
            if (matchesGlob(mapping.match(), groupArtifact)) {
                return mapping;
            }
        }
        return null;
    }

    /** {@code *} matches any run of characters; every other character is literal. */
    static boolean matchesGlob(String glob, String value) {
        StringBuilder regex = new StringBuilder();
        String[] parts = glob.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return value.matches(regex.toString());
    }

    private static String artifactIdOf(String groupArtifact) {
        int colon = groupArtifact.indexOf(':');
        return colon < 0 ? groupArtifact : groupArtifact.substring(colon + 1);
    }

    private static String substitute(String template, String version, String filePath, String artifactId) {
        if (template == null) {
            return null;
        }
        String result = template.replace("{version}", version);
        if (filePath != null) {
            result = result.replace("{file_path}", filePath);
        }
        if (artifactId != null) {
            result = result.replace("{artifactId}", artifactId);
        }
        return result;
    }
}
