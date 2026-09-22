package org.log2code.analyzer.config;

import java.util.List;

/** Analyzer configuration (0.11, {@code config/analyzer.yml}). All paths are relative to {@code log2code/}. */
public record AnalyzerConfig(Project project, Context context, Dependencies dependencies, OpenSearch opensearch) {

    public AnalyzerConfig {
        if (project == null) {
            throw new IllegalArgumentException("config: 'project' section is required");
        }
        if (context == null) {
            context = new Context(5, 10);
        }
        if (dependencies == null) {
            dependencies = new Dependencies(List.of(), List.of(), List.of());
        }
        if (opensearch == null) {
            opensearch = new OpenSearch("http://localhost:9200");
        }
    }

    public record Project(String name, String path, List<String> includeModules, List<String> excludeModules) {

        public Project {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("config: 'project.name' is required");
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("config: 'project.path' is required");
            }
            includeModules = includeModules == null ? List.of() : List.copyOf(includeModules);
            excludeModules = excludeModules == null ? List.of() : List.copyOf(excludeModules);
        }
    }

    public record Context(int snippetLines, int maxPrecedingStatements) {
    }

    public record Dependencies(List<String> include, List<String> exclude, List<String> autoSelectFromLogs) {

        public Dependencies {
            include = include == null ? List.of() : List.copyOf(include);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
            autoSelectFromLogs = autoSelectFromLogs == null ? List.of() : List.copyOf(autoSelectFromLogs);
        }
    }

    public record OpenSearch(String url) {

        public OpenSearch {
            if (url == null || url.isBlank()) {
                url = "http://localhost:9200";
            }
        }
    }
}
