package org.log2code.eval.truth;

import java.util.List;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Label;

/**
 * Ground truth of one event, by priority (T33 step 2): a manual label, then a reliable oracle position,
 * else none. An oracle position (class, line) that no applicable statement covers is reported as
 * {@code not in catalog}: that measures the catalog's coverage, not the matcher.
 */
public final class GroundTruthResolver {

    private final CatalogView catalog;

    public GroundTruthResolver(CatalogView catalog) {
        this.catalog = catalog;
    }

    /** @param label the manual label of this event, or {@code null} */
    public TruthResult resolve(EnrichedLog log, Label label) {
        if (label != null) {
            TruthResult manual = fromLabel(log, label);
            if (manual.known()) {
                return manual;
            }
        }
        return fromOracle(log);
    }

    private TruthResult fromLabel(EnrichedLog log, Label label) {
        if (label.verdict() == null) {
            return TruthResult.NONE;
        }
        switch (label.verdict()) {
            case Label.VERDICT_CORRECT -> {
                // the label's snapshot of the prediction first: the log may have been re-ingested since
                String predicted = label.predictedStatementId();
                if (isBlank(predicted) && log.match() != null) {
                    predicted = log.match().statementId();
                }
                return isBlank(predicted) ? TruthResult.NONE : TruthResult.of(TruthSource.MANUAL, List.of(predicted));
            }
            case Label.VERDICT_INCORRECT -> {
                // "incorrect" without a correct statement says only that the prediction is wrong: no usable truth
                return isBlank(label.correctStatementId())
                    ? TruthResult.NONE : TruthResult.of(TruthSource.MANUAL, List.of(label.correctStatementId()));
            }
            case Label.VERDICT_NOT_IN_CATALOG -> {
                return TruthResult.notInCatalog(TruthSource.MANUAL);
            }
            default -> {
                return TruthResult.NONE;
            }
        }
    }

    private TruthResult fromOracle(EnrichedLog log) {
        GroundTruth truth = log.groundTruth();
        if (truth == null || !Boolean.TRUE.equals(truth.reliable()) || truth.className() == null || truth.line() == null) {
            return TruthResult.NONE;
        }
        List<CatalogEntry> statements = catalog.statementsAt(log.service(), truth.className(), truth.line());
        if (statements.isEmpty()) {
            return TruthResult.notInCatalog(TruthSource.ORACLE);
        }
        return TruthResult.of(TruthSource.ORACLE, statements.stream().map(CatalogEntry::statementId).toList());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
