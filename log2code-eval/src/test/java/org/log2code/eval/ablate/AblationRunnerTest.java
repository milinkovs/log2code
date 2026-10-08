package org.log2code.eval.ablate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.eval.ablate.AblationResult.VariantResult;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.replay.ReplayDataset;
import org.log2code.eval.replay.SyntheticDatasetAccess;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

/**
 * The seven variants over the three-event synthetic dataset ({@code SyntheticDataset}), each outcome worked out by hand:
 *
 * <ul>
 *   <li>{@code full}: s1, s2 by the logger, s3 (dynamic) at exactly 0.35 = logger 0.20 + level 0.10 + throwable 0.05: 3 of 3;</li>
 *   <li>{@code no_logger}: s1 and s2 score alike, the smaller id wins (e1 right, e2 wrong); s3 falls to 0.15 and is unmatched: 1;</li>
 *   <li>{@code no_level}: the logger still separates s1 and s2; s3 falls to 0.25 and is unmatched: 2;</li>
 *   <li>{@code logger_candidates_only}: every event's own statement is a logger candidate: 3;</li>
 *   <li>{@code token_candidates_only}: s3 has no tokens, so e3 has no candidate: 2;</li>
 *   <li>{@code no_specificity}: s1 and s2 have the same specificity anyway: 3;</li>
 *   <li>{@code regex_only}: like {@code no_logger}, and s3 scores 0: 1.</li>
 * </ul>
 */
class AblationRunnerTest {

    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));

    @Test
    void everyVariantMatchesItsHandComputedOutcome() {
        ReplayDataset dataset = SyntheticDatasetAccess.dataset();

        AblationResult result = AblationRunner.run(dataset, CONFIG, Variants.standard());

        Map<String, Integer> expectedCorrect = new LinkedHashMap<>();
        expectedCorrect.put("full", 3);
        expectedCorrect.put("no_logger", 1);
        expectedCorrect.put("no_level", 2);
        expectedCorrect.put("logger_candidates_only", 3);
        expectedCorrect.put("token_candidates_only", 2);
        expectedCorrect.put("no_specificity", 3);
        expectedCorrect.put("regex_only", 1);

        Map<String, Integer> actualCorrect = new LinkedHashMap<>();
        for (VariantResult v : result.variants()) {
            actualCorrect.put(v.variant().id(), v.score().correctAt1());
        }
        assertThat(actualCorrect).isEqualTo(expectedCorrect);
    }

    @Test
    void coverageFollowsWhichEventsLoseTheirPrediction() {
        AblationResult result = AblationRunner.run(SyntheticDatasetAccess.dataset(), CONFIG, Variants.standard());

        assertThat(scoreOf(result, "full").covered()).isEqualTo(3);
        assertThat(scoreOf(result, "no_logger").covered()).isEqualTo(2);   // e3 unmatched
        assertThat(scoreOf(result, "no_level").covered()).isEqualTo(2);    // e3 unmatched
        assertThat(scoreOf(result, "token_candidates_only").covered()).isEqualTo(2);
        assertThat(scoreOf(result, "no_specificity").covered()).isEqualTo(3);
    }

    @Test
    void theFullVariantIsFirstAndEqualsTheDirectScore() {
        ReplayDataset dataset = SyntheticDatasetAccess.dataset();

        AblationResult result = AblationRunner.run(dataset, CONFIG, Variants.standard());

        assertThat(result.variants().get(0).variant().id()).isEqualTo(Variants.FULL);
        assertThat(result.full().score()).isEqualTo(dataset.score(CONFIG, CandidateMode.BOTH));
        assertThat(result.datasetId()).isEqualTo("syn-01");
        assertThat(result.base()).isSameAs(CONFIG);
    }

    @Test
    void wrongPredictionsInTheLoggerlessVariantsAreTies() {
        AblationResult result = AblationRunner.run(SyntheticDatasetAccess.dataset(), CONFIG, Variants.standard());

        Score noLogger = scoreOf(result, "no_logger");

        // e2 is wrong because s1 and s2 score exactly alike; e3 has no prediction at all
        assertThat(noLogger.tiedErrors()).isEqualTo(1);
        assertThat(noLogger.unmatchedErrors()).isEqualTo(1);
        assertThat(noLogger.errors()).isEqualTo(2);
    }

    private static Score scoreOf(AblationResult result, String id) {
        return result.variants().stream().filter(v -> v.variant().id().equals(id)).findFirst().orElseThrow().score();
    }

    @Test
    void anEmptyVariantListGivesAnEmptyResult() {
        AblationResult result = AblationRunner.run(SyntheticDatasetAccess.dataset(), CONFIG, List.of());

        assertThat(result.variants()).isEmpty();
    }
}
