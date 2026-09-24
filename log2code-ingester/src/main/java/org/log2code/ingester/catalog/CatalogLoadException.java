package org.log2code.ingester.catalog;

/**
 * Thrown by {@link CatalogIndex#load} when {@code log2code-runs} has no {@code AnalysisRun} for the
 * requested project version yet - the ingester (T21) needs to translate this into its own exit-code-1
 * "run the analyzer first" message; {@code CatalogIndex} itself only knows it cannot proceed.
 */
public final class CatalogLoadException extends RuntimeException {

    public CatalogLoadException(String message) {
        super(message);
    }
}
