package org.log2code.ingester.match;

/**
 * Which sources feed the candidate pool of 0.10 step 2. {@link #BOTH} is 0.10 itself (the union
 * {@code byLogger(L) ∪ topK_tokens(m)}) and the only mode ingest ever uses; the other two exist so T34's
 * ablations can measure what each source contributes. Scoring (step 3) and the decision (step 4) are
 * identical in every mode.
 */
public enum CandidateMode {
    /** {@code byLogger(L) ∪ topK_tokens(m)}: 0.10 step 2 as specified. */
    BOTH,
    /** Only {@code byLogger(L)}; an event whose logger is unresolved has no candidates. */
    LOGGER_ONLY,
    /** Only {@code topK_tokens(m)}; the logger still contributes to scoring, not to candidate generation. */
    TOKENS_ONLY
}
