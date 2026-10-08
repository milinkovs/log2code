package org.log2code.eval.metrics;

import java.util.List;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.MatchResult;
import org.log2code.eval.truth.CatalogView;
import org.log2code.eval.truth.TruthInfo;
import org.log2code.eval.truth.TruthResult;

/** Combines an ingested event with its ground truth into an {@link EventEvaluation}. */
public final class EventEvaluator {

    private final CatalogView catalog;

    public EventEvaluator(CatalogView catalog) {
        this.catalog = catalog;
    }

    public EventEvaluation evaluate(EnrichedLog log, TruthResult truth) {
        MatchResult match = log.match();
        String status = match == null || match.status() == null ? MatchResult.STATUS_UNMATCHED : match.status();
        String predicted = match == null ? null : match.statementId();
        List<Candidate> candidates = match == null || match.candidates() == null ? List.of() : match.candidates();

        TruthInfo info = null;
        if (truth.evaluable()) {
            String primary = predicted != null && truth.statementIds().contains(predicted)
                ? predicted : truth.statementIds().get(0);
            info = catalog.byId(primary).map(TruthInfo::of).orElseGet(() -> TruthInfo.unknown(primary));
        }

        return new EventEvaluation(log.logId(), log.sourceFile(), log.lineNumber(), log.service(),
            log.level() == null ? null : log.level().name(), log.message(), status, predicted,
            match == null ? null : match.confidence(), match == null ? null : match.confidenceLevel(),
            candidates, match == null ? null : match.scoreBreakdown(),
            truth.source(), truth.statementIds(), truth.notInCatalog(), info, rankOf(truth, candidates));
    }

    private static int rankOf(TruthResult truth, List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            if (truth.statementIds().contains(candidates.get(i).statementId())) {
                return i + 1;
            }
        }
        return 0;
    }
}
