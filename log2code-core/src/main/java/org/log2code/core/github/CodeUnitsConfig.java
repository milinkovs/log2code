package org.log2code.core.github;

import java.util.List;
import java.util.Map;

/**
 * Parsed {@code config/code-units.yml} (T15, 0.11): GitHub repo/ref/path mappings for the project
 * and for dependencies, consumed by {@link GithubLinker}.
 */
public record CodeUnitsConfig(Map<String, ProjectMapping> project, List<DependencyMapping> dependencies) {

    public CodeUnitsConfig {
        project = project == null ? Map.of() : Map.copyOf(project);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /**
     * {@code ref}/{@code path} may use {@code {version}}/{@code {file_path}}. {@code repo} may be
     * {@code null}: {@link GithubLinker} then falls back to the project's own git remote (0.3, T07's
     * {@code GitRepo}), when one is supplied.
     */
    public record ProjectMapping(String repo, String ref, String path) {
    }

    /**
     * {@code match} is a glob (only {@code *}, matching any run of characters) over
     * {@code groupId:artifactId}. {@code ref}/{@code path} may use {@code {version}},
     * {@code {file_path}} and {@code {artifactId}}. The first mapping in the list whose
     * {@code match} matches wins.
     */
    public record DependencyMapping(String match, String repo, String ref, String path) {
    }
}
