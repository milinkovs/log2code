package org.log2code.core.opensearch;

import java.util.List;
import java.util.Objects;

/**
 * The 7 OpenSearch indices from 0.7, with an optional name prefix.
 * The prefix isolates indices created by tests, e.g. {@code it-<uuid>-log2code-catalog}.
 */
public final class IndexNames {

    public static final String CATALOG = "log2code-catalog";
    public static final String SOURCES = "log2code-sources";
    public static final String TYPES = "log2code-types";
    public static final String METHODS = "log2code-methods";
    public static final String RUNS = "log2code-runs";
    public static final String LOGS = "log2code-logs";
    public static final String LABELS = "log2code-labels";

    /** Base (unprefixed) names, in a stable order. */
    public static final List<String> BASE_NAMES = List.of(CATALOG, SOURCES, TYPES, METHODS, RUNS, LOGS, LABELS);

    private final String prefix;

    public IndexNames() {
        this("");
    }

    public IndexNames(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    public String prefix() {
        return prefix;
    }

    public String catalog() {
        return prefix + CATALOG;
    }

    public String sources() {
        return prefix + SOURCES;
    }

    public String types() {
        return prefix + TYPES;
    }

    public String methods() {
        return prefix + METHODS;
    }

    public String runs() {
        return prefix + RUNS;
    }

    public String logs() {
        return prefix + LOGS;
    }

    public String labels() {
        return prefix + LABELS;
    }

    /** All 7 resolved (prefixed) index names, in the same order as {@link #BASE_NAMES}. */
    public List<String> all() {
        return BASE_NAMES.stream().map(base -> prefix + base).toList();
    }

    /** Strips the prefix from a resolved name, giving back the base name used to look up its mapping resource. */
    public String baseNameOf(String resolvedName) {
        return resolvedName.startsWith(prefix) ? resolvedName.substring(prefix.length()) : resolvedName;
    }
}
