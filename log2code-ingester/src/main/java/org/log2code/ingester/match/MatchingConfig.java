package org.log2code.ingester.match;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

/**
 * Root of {@code config/matching.yml} (0.10, T20 step 1): the weights and thresholds of 0.10 step 3-4,
 * the candidate-generation limits of 0.10 step 2, and the oracle's {@code unreliable-callers} list (T18
 * step 5's final home). Field names follow the YAML verbatim - {@code weights}/{@code thresholds}/
 * {@code candidates} use snake_case, {@code oracle.unreliable-callers} uses a hyphen - so every field
 * that needs translating from its Java name carries an explicit {@link JsonProperty} rather than a single
 * naming strategy (no one strategy produces both spellings).
 */
public record MatchingConfig(Weights weights, Thresholds thresholds, Candidates candidates, Oracle oracle) {

    /** 0.10 step 3's per-component point values. */
    public record Weights(
        @JsonProperty("regex_full") double regexFull,
        @JsonProperty("regex_prefix") double regexPrefix,
        @JsonProperty("specificity_max") double specificityMax,
        @JsonProperty("specificity_k") double specificityK,
        @JsonProperty("logger_exact") double loggerExact,
        @JsonProperty("logger_hierarchy") double loggerHierarchy,
        @JsonProperty("logger_abbrev_multi") double loggerAbbrevMulti,
        @JsonProperty("logger_conflict") double loggerConflict,
        @JsonProperty("level_equal") double levelEqual,
        @JsonProperty("level_dynamic") double levelDynamic,
        @JsonProperty("level_conflict") double levelConflict,
        @JsonProperty("throwable_consistent") double throwableConsistent,
        @JsonProperty("throwable_inconsistent") double throwableInconsistent) {
    }

    /** 0.10 step 4's decision thresholds. */
    public record Thresholds(
        @JsonProperty("min_score") double minScore,
        @JsonProperty("ambiguity_margin") double ambiguityMargin,
        @JsonProperty("ambiguous_penalty") double ambiguousPenalty,
        double high,
        double medium) {
    }

    /** 0.10 step 2's candidate-generation limits. */
    public record Candidates(
        @JsonProperty("top_k_tokens") int topKTokens,
        @JsonProperty("max_candidates") int maxCandidates) {
    }

    /** T18 step 5's caller patterns that mark an oracle {@code ground_truth} as unreliable. */
    public record Oracle(@JsonProperty("unreliable-callers") Set<String> unreliableCallers) {
        public Oracle {
            unreliableCallers = (unreliableCallers == null) ? Set.of() : Set.copyOf(unreliableCallers);
        }
    }
}
