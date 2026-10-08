package org.log2code.eval.ablate;

import java.util.ArrayList;
import java.util.List;
import org.log2code.eval.ablate.AblationResult.VariantResult;
import org.log2code.eval.replay.ReplayDataset;
import org.log2code.ingester.match.MatchingConfig;

/** Runs every {@link Variant} over one {@link ReplayDataset} (T34 step 1). */
public final class AblationRunner {

    private AblationRunner() {
    }

    public static AblationResult run(ReplayDataset dataset, MatchingConfig base, List<Variant> variants) {
        List<VariantResult> results = new ArrayList<>();
        for (Variant variant : variants) {
            results.add(new VariantResult(variant, dataset.score(variant.config(base), variant.candidateMode())));
        }
        return new AblationResult(dataset.datasetId(), base, results, dataset.dominantTruthKeys());
    }
}
