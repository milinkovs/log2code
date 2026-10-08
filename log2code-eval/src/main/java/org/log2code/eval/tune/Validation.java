package org.log2code.eval.tune;

import org.log2code.core.model.CodeVersion;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.replay.ReplayDataset;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;

/**
 * T34 step 3: the baseline and the proposed configuration scored once on the held-out dataset. Which one is
 * "not worse" is decided here and nowhere else: the proposal must not have fewer correct top-1 events and
 * must still meet the {@code high} precision constraint.
 */
public final class Validation {

    public enum Verdict { BETTER, EQUAL, WORSE }

    /**
     * @param identical the proposal is the baseline itself (the search found nothing better), so both scores are the same run
     */
    public record Result(String datasetId, CodeVersion code, Score baseline, Score proposed, Verdict verdict,
                         boolean constraintMet, double minHighPrecision, boolean identical) {

        /** The proposal may replace {@code config/matching.yml}: not worse on accuracy@1 and the constraint holds. */
        public boolean notWorse() {
            return verdict != Verdict.WORSE && constraintMet;
        }
    }

    private Validation() {
    }

    public static Result run(ReplayDataset dataset, MatchingConfig baseline, MatchingConfig proposed, double minHighPrecision) {
        Score baselineScore = dataset.score(baseline, CandidateMode.BOTH);
        Score proposedScore = dataset.score(proposed, CandidateMode.BOTH);
        return of(dataset.datasetId(), dataset.code(), baselineScore, proposedScore, minHighPrecision, baseline.equals(proposed));
    }

    public static Result of(String datasetId, CodeVersion code, Score baseline, Score proposed, double minHighPrecision) {
        return of(datasetId, code, baseline, proposed, minHighPrecision, false);
    }

    public static Result of(String datasetId, CodeVersion code, Score baseline, Score proposed, double minHighPrecision,
                            boolean identical) {
        Verdict verdict = proposed.correctAt1() > baseline.correctAt1() ? Verdict.BETTER
            : proposed.correctAt1() == baseline.correctAt1() ? Verdict.EQUAL : Verdict.WORSE;
        Double precision = proposed.precisionHigh();
        boolean constraintMet = precision != null && precision >= minHighPrecision;
        return new Result(datasetId, code, baseline, proposed, verdict, constraintMet, minHighPrecision, identical);
    }
}
