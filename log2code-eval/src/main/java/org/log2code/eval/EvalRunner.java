package org.log2code.eval;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Label;
import org.log2code.eval.data.EvalDataSource;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.eval.metrics.EventEvaluator;
import org.log2code.eval.metrics.MetricsCalculator;
import org.log2code.eval.report.ErrorSampler;
import org.log2code.eval.truth.CatalogView;
import org.log2code.eval.truth.GroundTruthResolver;

/** One evaluation of one ingested dataset (T33): ground truth, per-event outcome, metrics, error sample. */
public final class EvalRunner {

    public static final int DEFAULT_ERROR_SAMPLES = 25;
    public static final long DEFAULT_SEED = 42;

    private EvalRunner() {
    }

    public static EvalResult run(EvalDataSource source, String datasetId, int errorSamples, long seed) throws IOException {
        List<EnrichedLog> logs = source.events(datasetId);
        if (logs.isEmpty()) {
            throw new EvalUserException("dataset '" + datasetId + "' has no events in log2code-logs; run "
                + "'scripts/ingester.sh ingest --dataset datasets/" + datasetId + "' first.");
        }
        CodeVersion code = singleCodeVersion(datasetId, logs);

        CatalogView catalog = source.catalog(code);
        Map<String, Label> labels = source.labels(datasetId);

        GroundTruthResolver resolver = new GroundTruthResolver(catalog);
        EventEvaluator evaluator = new EventEvaluator(catalog);
        List<EventEvaluation> events = logs.stream()
            .map(log -> evaluator.evaluate(log, resolver.resolve(log, labels.get(log.logId()))))
            .toList();

        return new EvalResult(datasetId, code, MetricsCalculator.compute(datasetId, code, events), events,
            MetricsCalculator.statementRows(events), ErrorSampler.errorTypes(events),
            ErrorSampler.sample(events, errorSamples, seed), catalog);
    }

    private static CodeVersion singleCodeVersion(String datasetId, List<EnrichedLog> logs) {
        Set<CodeVersion> versions = logs.stream().map(EnrichedLog::code).collect(Collectors.toSet());
        if (versions.size() != 1 || versions.contains(null)) {
            throw new EvalUserException("dataset '" + datasetId + "' must have exactly one code version, found " + versions);
        }
        return versions.iterator().next();
    }
}
