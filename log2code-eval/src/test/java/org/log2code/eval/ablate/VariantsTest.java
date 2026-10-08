package org.log2code.eval.ablate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.log2code.eval.tune.ParameterSpace;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;
import org.log2code.ingester.match.MatchingConfigLoader;

class VariantsTest {

    private static final MatchingConfig FULL = MatchingConfigLoader.load(Path.of("..", "config", "matching.yml"));

    @Test
    void thereAreTheSevenVariantsOfT34InTheirOrder() {
        assertThat(Variants.standard()).extracting(Variant::id).containsExactly(
            "full", "no_logger", "no_level", "logger_candidates_only", "token_candidates_only", "no_specificity", "regex_only");
    }

    @Test
    void theFullVariantChangesNothing() {
        Variant full = variant("full");

        assertThat(full.config(FULL)).isEqualTo(FULL);
        assertThat(full.candidateMode()).isEqualTo(CandidateMode.BOTH);
    }

    @Test
    void noLoggerZeroesExactlyTheFourLoggerWeights() {
        assertThat(changed(variant("no_logger"))).containsOnlyKeys(
            "logger_exact", "logger_hierarchy", "logger_abbrev_multi", "logger_conflict")
            .allSatisfy((name, value) -> assertThat(value).isZero());
    }

    @Test
    void noLevelZeroesExactlyTheThreeLevelWeights() {
        assertThat(changed(variant("no_level"))).containsOnlyKeys("level_equal", "level_dynamic", "level_conflict")
            .allSatisfy((name, value) -> assertThat(value).isZero());
    }

    @Test
    void noSpecificityZeroesOnlySpecificityMax() {
        assertThat(changed(variant("no_specificity"))).containsOnlyKeys("specificity_max")
            .allSatisfy((name, value) -> assertThat(value).isZero());
    }

    @Test
    void regexOnlyKeepsRegexAndSpecificityAndZeroesTheOtherNineWeights() {
        Map<String, Double> changed = changed(variant("regex_only"));

        assertThat(changed).hasSize(9).allSatisfy((name, value) -> assertThat(value).isZero());
        assertThat(changed.keySet()).doesNotContain("regex_full", "regex_prefix", "specificity_max", "specificity_k");
    }

    @Test
    void theCandidateVariantsChangeOnlyTheCandidateSource() {
        Variant logger = variant("logger_candidates_only");
        Variant tokens = variant("token_candidates_only");

        assertThat(logger.config(FULL)).isEqualTo(FULL);
        assertThat(tokens.config(FULL)).isEqualTo(FULL);
        assertThat(logger.candidateMode()).isEqualTo(CandidateMode.LOGGER_ONLY);
        assertThat(tokens.candidateMode()).isEqualTo(CandidateMode.TOKENS_ONLY);
    }

    @Test
    void noVariantTouchesAThresholdOrACandidateLimit() {
        for (Variant variant : Variants.standard()) {
            MatchingConfig config = variant.config(FULL);

            assertThat(config.thresholds()).as(variant.id()).isEqualTo(FULL.thresholds());
            assertThat(config.candidates()).as(variant.id()).isEqualTo(FULL.candidates());
            assertThat(config.oracle()).as(variant.id()).isEqualTo(FULL.oracle());
        }
    }

    @Test
    void everyVariantHasADescriptionAndATitle() {
        for (Variant variant : Variants.standard()) {
            assertThat(variant.title()).isNotBlank();
            assertThat(variant.description()).isNotBlank();
        }
    }

    private static Variant variant(String id) {
        List<Variant> all = Variants.standard();
        return all.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    /** Parameters whose value differs from the full configuration, by name. */
    private static Map<String, Double> changed(Variant variant) {
        double[] before = ParameterSpace.read(FULL);
        double[] after = ParameterSpace.read(variant.config(FULL));
        return java.util.stream.IntStream.range(0, before.length)
            .filter(i -> before[i] != after[i])
            .boxed()
            .collect(Collectors.toMap(i -> ParameterSpace.PARAMETERS.get(i).name(), i -> after[i]));
    }
}
