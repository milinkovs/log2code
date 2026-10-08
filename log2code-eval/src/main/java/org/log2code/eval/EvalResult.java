package org.log2code.eval;

import java.util.List;
import org.log2code.core.model.CodeVersion;
import org.log2code.eval.metrics.EvalMetrics;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.eval.metrics.StatementRow;
import org.log2code.eval.report.ErrorSample;
import org.log2code.eval.truth.CatalogView;

/** Everything one {@code eval run} produces; the report writer turns it into files. */
public record EvalResult(
    String datasetId,
    CodeVersion code,
    EvalMetrics metrics,
    List<EventEvaluation> events,
    List<StatementRow> statements,
    List<ErrorSample> errorTypes,
    List<ErrorSample> errorSamples,
    CatalogView catalog
) {
}
