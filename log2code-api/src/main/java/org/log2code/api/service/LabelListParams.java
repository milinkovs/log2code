package org.log2code.api.service;

import org.log2code.core.model.Label;

/**
 * Parsed, validated parameters of {@code GET /api/labels?datasetId=&verdict=} (T25 step 2).
 * {@code size} above {@link #MAX_SIZE} is silently clamped (same pattern as {@link LogSearchParams}).
 */
public record LabelListParams(String datasetId, String verdict, int size, String searchAfter) {

    public static final int DEFAULT_SIZE = 100;
    public static final int MAX_SIZE = 500;

    public LabelListParams {
        if (verdict != null && !verdict.isBlank() && !isKnownVerdict(verdict)) {
            throw new IllegalArgumentException("verdict must be 'correct', 'incorrect', or 'not_in_catalog': " + verdict);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        size = Math.min(size, MAX_SIZE);
    }

    private static boolean isKnownVerdict(String verdict) {
        return verdict.equals(Label.VERDICT_CORRECT) || verdict.equals(Label.VERDICT_INCORRECT)
            || verdict.equals(Label.VERDICT_NOT_IN_CATALOG);
    }
}
