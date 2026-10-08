package org.log2code.eval.ablate;

import java.util.function.UnaryOperator;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;

/**
 * One matcher variant of the ablation (T34 step 1): the configuration to score with and where the candidates
 * come from. Thresholds are never part of a variant, so every variant uses the same decision rule.
 *
 * @param id          stable key, used in the output files
 * @param title       name shown in the report
 * @param description what the variant takes away, in the report's language
 * @param transform   turns the full configuration into this variant's configuration
 */
public record Variant(String id, String title, String description, UnaryOperator<MatchingConfig> transform,
                      CandidateMode candidateMode) {

    public MatchingConfig config(MatchingConfig full) {
        return transform.apply(full);
    }
}
