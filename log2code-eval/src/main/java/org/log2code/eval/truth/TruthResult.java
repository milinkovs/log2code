package org.log2code.eval.truth;

import java.util.List;

/**
 * The ground truth of one event: the set of statements that may have produced it, or the fact that the
 * truth is known but the statement is not in the catalog ({@code gt_not_in_catalog}), or no truth at all.
 *
 * @param source       where the truth came from
 * @param statementIds sorted ids of the statements that count as correct (empty if none or not in catalog)
 * @param notInCatalog the truth is known, but no applicable catalog statement matches it
 */
public record TruthResult(TruthSource source, List<String> statementIds, boolean notInCatalog) {

    public static final TruthResult NONE = new TruthResult(TruthSource.NONE, List.of(), false);

    public TruthResult {
        statementIds = statementIds.stream().sorted().distinct().toList();
    }

    public static TruthResult of(TruthSource source, List<String> statementIds) {
        return new TruthResult(source, statementIds, false);
    }

    public static TruthResult notInCatalog(TruthSource source) {
        return new TruthResult(source, List.of(), true);
    }

    /** The event has a ground truth (including the "not in catalog" outcome). */
    public boolean known() {
        return source != TruthSource.NONE && (notInCatalog || !statementIds.isEmpty());
    }

    /** The truth points at catalog statements, so the event counts towards accuracy. */
    public boolean evaluable() {
        return !statementIds.isEmpty();
    }

    /** Stable key of the truth, used to group events per unique statement. */
    public String key() {
        return String.join("+", statementIds);
    }
}
