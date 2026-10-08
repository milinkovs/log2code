package org.log2code.eval.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.MatchResult;
import org.log2code.eval.metrics.EvalMetrics.BreakdownRow;
import org.log2code.eval.metrics.EvalMetrics.Counts;
import org.log2code.eval.metrics.EvalMetrics.GroupRow;
import org.log2code.eval.metrics.EvalMetrics.Headline;
import org.log2code.eval.metrics.EvalMetrics.UniqueStatements;
import org.log2code.eval.truth.TruthInfo;
import org.log2code.eval.truth.TruthSource;

/** Computes the metrics of T33 step 3 from a list of {@link EventEvaluation}. Pure: no I/O, no randomness. */
public final class MetricsCalculator {

    static final String UNSUPPORTED_TEMPLATE_KIND = "unsupported";
    static final String UNKNOWN = "unknown";
    static final int TOP_ARTIFACTS = 15;
    static final String OTHER_ARTIFACTS = "(other artifacts)";
    private static final List<String> CONFIDENCE_LEVELS = List.of(
        MatchResult.CONFIDENCE_HIGH, MatchResult.CONFIDENCE_MEDIUM, MatchResult.CONFIDENCE_LOW);
    private static final List<String> STATUSES = List.of(
        MatchResult.STATUS_MATCHED, MatchResult.STATUS_AMBIGUOUS, MatchResult.STATUS_UNMATCHED);

    private MetricsCalculator() {
    }

    public static EvalMetrics compute(String datasetId, CodeVersion code, List<EventEvaluation> events) {
        List<EventEvaluation> evaluable = events.stream().filter(EventEvaluation::evaluable).toList();

        Counts counts = counts(events, evaluable);
        Headline headline = headline(events, evaluable);

        Map<String, List<BreakdownRow>> breakdowns = new LinkedHashMap<>();
        breakdowns.put("code_unit_type", breakdown(evaluable, e -> info(e, i -> i.codeUnitType())));
        breakdowns.put("artifact", limitArtifacts(breakdown(evaluable, e -> info(e, i -> i.artifact())), evaluable));
        breakdowns.put("logging_api", breakdown(evaluable, e -> info(e, i -> i.loggingApi())));
        breakdowns.put("template_kind", breakdown(evaluable, e -> info(e, i -> i.templateKind())));
        breakdowns.put("level", breakdown(evaluable, e -> e.level() == null ? UNKNOWN : e.level()));
        breakdowns.put("service", breakdown(evaluable, e -> e.service() == null ? UNKNOWN : e.service()));

        return new EvalMetrics(datasetId, code == null ? null : code.name(), code == null ? null : code.version(),
            counts, headline, confidenceTable(evaluable), statusTable(evaluable), uniqueStatements(evaluable), breakdowns);
    }

    /** One row per correct statement, most frequent first (ties by key). */
    public static List<StatementRow> statementRows(List<EventEvaluation> events) {
        Map<String, List<EventEvaluation>> groups = groupByTruth(events.stream().filter(EventEvaluation::evaluable).toList());
        List<StatementRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<EventEvaluation>> group : groups.entrySet()) {
            List<EventEvaluation> members = group.getValue();
            Map<String, Integer> predictions = new LinkedHashMap<>();
            for (EventEvaluation member : members) {
                predictions.merge(member.predictedStatementId() == null || !member.covered() ? "" : member.predictedStatementId(), 1, Integer::sum);
            }
            Map.Entry<String, Integer> top = predictions.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .orElseThrow();
            EventEvaluation first = members.get(0);
            rows.add(new StatementRow(group.getKey(), first.truthStatementIds(), first.truthInfo(), members.size(),
                (int) members.stream().filter(EventEvaluation::covered).count(),
                (int) members.stream().filter(EventEvaluation::correctAt1).count(),
                (int) members.stream().filter(EventEvaluation::correctAt3).count(),
                top.getKey().isEmpty() ? null : top.getKey(), top.getValue()));
        }
        rows.sort(Comparator.comparingInt(StatementRow::events).reversed().thenComparing(StatementRow::key));
        return rows;
    }

    private static Counts counts(List<EventEvaluation> events, List<EventEvaluation> evaluable) {
        return new Counts(
            events.size(),
            count(events, e -> MatchResult.STATUS_MATCHED.equals(e.status())),
            count(events, e -> MatchResult.STATUS_AMBIGUOUS.equals(e.status())),
            count(events, e -> !e.covered()),
            count(events, e -> e.truthKnown() && e.truthSource() == TruthSource.MANUAL),
            count(events, e -> e.truthKnown() && e.truthSource() == TruthSource.ORACLE),
            count(events, e -> !e.truthKnown()),
            count(events, e -> e.truthKnown() && e.truthNotInCatalog()),
            evaluable.size(),
            count(evaluable, MetricsCalculator::unsupported));
    }

    private static Headline headline(List<EventEvaluation> events, List<EventEvaluation> evaluable) {
        List<EventEvaluation> known = events.stream().filter(EventEvaluation::truthKnown).toList();
        List<EventEvaluation> notInCatalog = known.stream().filter(EventEvaluation::truthNotInCatalog).toList();
        List<EventEvaluation> coveredEvaluable = evaluable.stream().filter(EventEvaluation::covered).toList();
        List<EventEvaluation> supported = evaluable.stream().filter(e -> !unsupported(e)).toList();

        return new Headline(
            ratioOrZero(count(events, EventEvaluation::covered), events.size()),
            ratio(count(known, EventEvaluation::covered), known.size()),
            ratio(count(evaluable, EventEvaluation::correctAt1), evaluable.size()),
            ratio(count(evaluable, EventEvaluation::correctAt3), evaluable.size()),
            ratio(count(coveredEvaluable, EventEvaluation::correctAt3), coveredEvaluable.size()),
            ratio(count(coveredEvaluable, EventEvaluation::correctAt1), coveredEvaluable.size()),
            ratio(count(supported, EventEvaluation::correctAt1), supported.size()),
            ratioOrZero(count(events, e -> MatchResult.STATUS_AMBIGUOUS.equals(e.status())), events.size()),
            ratio(count(evaluable, e -> MatchResult.STATUS_AMBIGUOUS.equals(e.status())), evaluable.size()),
            ratio(notInCatalog.size(), known.size()),
            ratio(count(notInCatalog, EventEvaluation::covered), notInCatalog.size()));
    }

    /** Precision per stored confidence level over matched and ambiguous evaluable events (calibration). */
    private static List<GroupRow> confidenceTable(List<EventEvaluation> evaluable) {
        List<EventEvaluation> covered = evaluable.stream().filter(EventEvaluation::covered).toList();
        List<GroupRow> rows = new ArrayList<>();
        for (String level : CONFIDENCE_LEVELS) {
            rows.add(groupRow(level, covered.stream().filter(e -> level.equals(e.confidenceLevel())).toList()));
        }
        return rows;
    }

    private static List<GroupRow> statusTable(List<EventEvaluation> evaluable) {
        List<GroupRow> rows = new ArrayList<>();
        for (String status : STATUSES) {
            rows.add(groupRow(status, evaluable.stream().filter(e -> status.equals(e.status())).toList()));
        }
        return rows;
    }

    private static GroupRow groupRow(String key, List<EventEvaluation> members) {
        int correct1 = count(members, EventEvaluation::correctAt1);
        int correct3 = count(members, EventEvaluation::correctAt3);
        return new GroupRow(key, members.size(), correct1, correct3, ratio(correct1, members.size()), ratio(correct3, members.size()));
    }

    private static List<BreakdownRow> breakdown(List<EventEvaluation> evaluable, Function<EventEvaluation, String> keyOf) {
        Map<String, List<EventEvaluation>> groups = new LinkedHashMap<>();
        for (EventEvaluation event : evaluable) {
            groups.computeIfAbsent(keyOf.apply(event), k -> new ArrayList<>()).add(event);
        }
        List<BreakdownRow> rows = new ArrayList<>();
        groups.forEach((key, members) -> rows.add(breakdownRow(key, members)));
        rows.sort(Comparator.comparingInt(BreakdownRow::events).reversed().thenComparing(BreakdownRow::key));
        return rows;
    }

    /** Keeps the {@value #TOP_ARTIFACTS} artifacts with the most events and folds the rest into one row. */
    private static List<BreakdownRow> limitArtifacts(List<BreakdownRow> rows, List<EventEvaluation> evaluable) {
        if (rows.size() <= TOP_ARTIFACTS) {
            return rows;
        }
        Set<String> kept = new HashSet<>();
        rows.stream().limit(TOP_ARTIFACTS).forEach(r -> kept.add(r.key()));
        List<EventEvaluation> rest = evaluable.stream().filter(e -> !kept.contains(info(e, i -> i.artifact()))).toList();
        List<BreakdownRow> limited = new ArrayList<>(rows.subList(0, TOP_ARTIFACTS));
        limited.add(breakdownRow(OTHER_ARTIFACTS, rest));
        return limited;
    }

    private static BreakdownRow breakdownRow(String key, List<EventEvaluation> members) {
        int covered = count(members, EventEvaluation::covered);
        int correct1 = count(members, EventEvaluation::correctAt1);
        int correct3 = count(members, EventEvaluation::correctAt3);
        Map<String, List<EventEvaluation>> statements = groupByTruth(members);
        int hit = (int) statements.values().stream().filter(g -> g.stream().anyMatch(EventEvaluation::correctAt1)).count();
        return new BreakdownRow(key, members.size(), covered, correct1, correct3, ratio(covered, members.size()),
            ratio(correct1, members.size()), ratio(correct3, members.size()), statements.size(), hit);
    }

    private static UniqueStatements uniqueStatements(List<EventEvaluation> evaluable) {
        Map<String, List<EventEvaluation>> groups = groupByTruth(evaluable);
        if (groups.isEmpty()) {
            return new UniqueStatements(0, 0, null, null, null, 0);
        }
        int hit = 0;
        double sum1 = 0;
        double sum3 = 0;
        int largest = 0;
        for (List<EventEvaluation> members : groups.values()) {
            if (members.stream().anyMatch(EventEvaluation::correctAt1)) {
                hit++;
            }
            sum1 += (double) count(members, EventEvaluation::correctAt1) / members.size();
            sum3 += (double) count(members, EventEvaluation::correctAt3) / members.size();
            largest = Math.max(largest, members.size());
        }
        return new UniqueStatements(groups.size(), hit, ratio(hit, groups.size()), sum1 / groups.size(), sum3 / groups.size(),
            (double) largest / evaluable.size());
    }

    private static Map<String, List<EventEvaluation>> groupByTruth(List<EventEvaluation> evaluable) {
        Map<String, List<EventEvaluation>> groups = new LinkedHashMap<>();
        for (EventEvaluation event : evaluable) {
            groups.computeIfAbsent(event.truthKey(), k -> new ArrayList<>()).add(event);
        }
        return groups;
    }

    private static boolean unsupported(EventEvaluation event) {
        return event.truthInfo() != null && UNSUPPORTED_TEMPLATE_KIND.equals(event.truthInfo().templateKind());
    }

    private static String info(EventEvaluation event, Function<TruthInfo, String> attribute) {
        String value = event.truthInfo() == null ? null : attribute.apply(event.truthInfo());
        return value == null ? UNKNOWN : value;
    }

    private static int count(List<EventEvaluation> events, Predicate<EventEvaluation> predicate) {
        return (int) events.stream().filter(predicate).count();
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static double ratioOrZero(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
