package org.log2code.eval.ablate;

import java.util.List;
import org.log2code.eval.metrics.Score;
import org.log2code.ingester.match.MatchingConfig;

/**
 * Result of one ablation: the score of every variant, the full matcher first.
 *
 * @param base          the configuration the variants were derived from ({@code config/matching.yml})
 * @param dominantTruth the {@link org.log2code.eval.replay.ReplayDataset#dominantTruthKeys() dominant statements}, as keys
 */
public record AblationResult(String datasetId, MatchingConfig base, List<VariantResult> variants,
                             List<String> dominantTruth) {

    /** @param score the variant's counts over the whole dataset */
    public record VariantResult(Variant variant, Score score) {
    }

    public VariantResult full() {
        return variants.stream().filter(v -> Variants.FULL.equals(v.variant().id())).findFirst().orElseThrow();
    }
}
