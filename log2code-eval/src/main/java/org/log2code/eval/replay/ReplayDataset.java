package org.log2code.eval.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.Label;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.MatchResult;
import org.log2code.eval.EvalUserException;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.eval.metrics.EventEvaluator;
import org.log2code.eval.metrics.Score;
import org.log2code.eval.truth.CatalogView;
import org.log2code.eval.truth.GroundTruthResolver;
import org.log2code.eval.truth.TruthResult;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.match.CandidateMode;
import org.log2code.ingester.match.Matcher;
import org.log2code.ingester.match.MatchingConfig;

/**
 * The events of one dataset assembled in memory, with the ground truth of each resolved once (T34 step 1).
 * Everything an ablation, a tuning trial or a validation changes is the matcher: {@link #evaluate} runs a
 * fresh {@link Matcher} over the same events and evaluates it against the same ground truth, so nothing is
 * written to OpenSearch and the result for the {@code config/matching.yml} weights equals what
 * {@code ingester ingest} followed by {@code eval run} reports.
 *
 * <p>Manual labels are applied through their stored snapshot of the prediction ({@code predicted_statement_id}),
 * which the API always fills, so the ground truth does not depend on which weights are being tried.
 */
public final class ReplayDataset {

    /** How many of the most frequent correct statements the control accuracy leaves out (ADR-045). */
    public static final int CONTROL_TOP = 2;

    private final String datasetId;
    private final CodeVersion code;
    private final CatalogIndex catalogIndex;
    private final CatalogView catalog;
    private final List<LogEvent> events;
    private final List<TruthResult> truths;
    private final EventEvaluator evaluator;
    private final List<String> dominantTruthKeys;

    public ReplayDataset(String datasetId, CodeVersion code, CatalogIndex catalogIndex, CatalogView catalog,
                         List<LogEvent> events, Map<String, Label> labels) {
        this.datasetId = datasetId;
        this.code = code;
        this.catalogIndex = catalogIndex;
        this.catalog = catalog;
        this.events = List.copyOf(events);
        this.evaluator = new EventEvaluator(catalog);

        GroundTruthResolver resolver = new GroundTruthResolver(catalog);
        List<TruthResult> resolved = new ArrayList<>(events.size());
        for (LogEvent event : this.events) {
            EnrichedLog unmatched = toEnriched(event, null);
            resolved.add(resolver.resolve(unmatched, labels.get(unmatched.logId())));
        }
        this.truths = List.copyOf(resolved);
        this.dominantTruthKeys = dominant(truths, CONTROL_TOP);
    }

    public String datasetId() {
        return datasetId;
    }

    public CodeVersion code() {
        return code;
    }

    public CatalogView catalog() {
        return catalog;
    }

    public int size() {
        return events.size();
    }

    /** Accuracy needs events whose ground truth is a catalog statement; fail early with a message the CLI can show. */
    public void requireGroundTruth() {
        if (truths.stream().noneMatch(TruthResult::evaluable)) {
            throw new EvalUserException("dataset '" + datasetId + "' has no event with a ground truth in the catalog; "
                + "ablations and tuning need an oracle dataset (manifest 'oracle: true') or manual labels.");
        }
    }

    /** Keys of the {@link #CONTROL_TOP} most frequent correct statements, most frequent first. */
    public List<String> dominantTruthKeys() {
        return dominantTruthKeys;
    }

    /** One matcher run over every event, in dataset order. */
    public List<EventEvaluation> evaluate(MatchingConfig config, CandidateMode mode) {
        Matcher matcher = new Matcher(catalogIndex, config, mode);
        List<EventEvaluation> result = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            LogEvent event = events.get(i);
            MatchResult match = matcher.match(event);
            result.add(evaluator.evaluate(toEnriched(event, match), truths.get(i)));
        }
        return result;
    }

    public Score score(MatchingConfig config, CandidateMode mode) {
        return Score.of(evaluate(config, mode), Set.copyOf(dominantTruthKeys));
    }

    private static List<String> dominant(List<TruthResult> truths, int top) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TruthResult truth : truths) {
            if (truth.evaluable()) {
                counts.merge(truth.key(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
            .limit(top)
            .map(Map.Entry::getKey)
            .toList();
    }

    private static EnrichedLog toEnriched(LogEvent event, MatchResult match) {
        return new EnrichedLog(
            StableIds.logId(event.datasetId(), event.sourceFile(), event.lineNumber()),
            event.timestamp(), event.timestampRaw(), event.datasetId(), event.sourceFile(), event.lineNumber(),
            event.lineCount(), event.sequence(), event.service(), event.module(), event.appName(), event.pid(),
            event.thread(), event.level(), event.loggerRaw(), event.logger(), event.message(), event.raw(),
            event.traceId(), event.spanId(), event.exception(), event.code(), match, event.groundTruth(),
            event.parserFormat(), null, null);
    }
}
