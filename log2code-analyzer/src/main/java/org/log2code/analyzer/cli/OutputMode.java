package org.log2code.analyzer.cli;

/** Where the {@code project} command's results go (global {@code --out} option). */
public enum OutputMode {
    OPENSEARCH,
    JSON,
    BOTH;

    public boolean writesToOpenSearch() {
        return this == OPENSEARCH || this == BOTH;
    }

    public boolean writesToJson() {
        return this == JSON || this == BOTH;
    }
}
