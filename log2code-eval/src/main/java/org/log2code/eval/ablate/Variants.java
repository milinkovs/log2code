package org.log2code.eval.ablate;

import java.util.List;
import org.log2code.eval.tune.ParameterSpace;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.MatchingConfig;

/**
 * The seven variants of T34 step 1. Each takes away one thing from the full matcher (ADR-045 fixes what
 * "one thing" means): a weight family set to zero, or one of the two candidate sources.
 */
public final class Variants {

    public static final String FULL = "full";

    private static final List<String> LOGGER_WEIGHTS =
        List.of("logger_exact", "logger_hierarchy", "logger_abbrev_multi", "logger_conflict");
    private static final List<String> LEVEL_WEIGHTS = List.of("level_equal", "level_dynamic", "level_conflict");
    private static final List<String> THROWABLE_WEIGHTS = List.of("throwable_consistent", "throwable_inconsistent");

    private Variants() {
    }

    public static List<Variant> standard() {
        return List.of(
            new Variant(FULL, "Pun matcher",
                "0.10 bez izmena: oba izvora kandidata i sve komponente bodovanja.",
                c -> c, CandidateMode.BOTH),
            new Variant("no_logger", "Bez loggera",
                "Težine `logger_exact`, `logger_hierarchy`, `logger_abbrev_multi` i `logger_conflict` su 0. Kandidati i dalje stižu iz oba izvora.",
                c -> zeroed(c, LOGGER_WEIGHTS), CandidateMode.BOTH),
            new Variant("no_level", "Bez nivoa",
                "Težine `level_equal`, `level_dynamic` i `level_conflict` su 0.",
                c -> zeroed(c, LEVEL_WEIGHTS), CandidateMode.BOTH),
            new Variant("logger_candidates_only", "Samo kandidati po loggeru",
                "Kandidati su samo `byLogger(L)`; kandidati po tokenima se ne generišu. Bodovanje je puno.",
                c -> c, CandidateMode.LOGGER_ONLY),
            new Variant("token_candidates_only", "Samo kandidati po tokenima",
                "Kandidati su samo `topK_tokens(m)`; kandidati po loggeru se ne generišu. Logger i dalje učestvuje u bodovanju.",
                c -> c, CandidateMode.TOKENS_ONLY),
            new Variant("no_specificity", "Bez specifičnosti",
                "Težina `specificity_max` je 0, pa dužina literalnog dela šablona ne daje poene.",
                c -> zeroed(c, List.of("specificity_max")), CandidateMode.BOTH),
            new Variant("regex_only", "Samo regex",
                "Ostaju `regex_full`, `regex_prefix` i `specificity`; logger, nivo i throwable (9 težina) su 0.",
                c -> zeroed(c, concat(LOGGER_WEIGHTS, LEVEL_WEIGHTS, THROWABLE_WEIGHTS)), CandidateMode.BOTH));
    }

    /** {@code config} with the named weights set to 0; every other weight, every threshold and the candidate limits stay. */
    static MatchingConfig zeroed(MatchingConfig config, List<String> weights) {
        double[] values = ParameterSpace.read(config);
        for (String name : weights) {
            values[ParameterSpace.indexOf(name)] = 0;
        }
        return ParameterSpace.apply(config, values);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        return java.util.stream.Stream.of(lists).flatMap(List::stream).toList();
    }
}
