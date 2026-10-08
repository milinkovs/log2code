package org.log2code.eval.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.log2code.eval.metrics.EventEvaluation;

/**
 * Error examples (T33 step 4). One frequent mistake can account for most wrong events (in {@code tune-02} a
 * single repeated message), so examples are drawn per <em>kind</em> of mistake, not per event: the sample is
 * random with a fixed seed, but every kind appears at most once and carries its event count.
 */
public final class ErrorSampler {

    private static final Comparator<EventEvaluation> BY_POSITION =
        Comparator.comparing(EventEvaluation::sourceFile).thenComparingInt(EventEvaluation::lineNumber);

    private ErrorSampler() {
    }

    /** Every kind of mistake with its example and event count, most frequent first. */
    public static List<ErrorSample> errorTypes(List<EventEvaluation> events) {
        List<EventEvaluation> errors = new ArrayList<>(events.stream()
            .filter(e -> e.evaluable() && !e.correctAt1())
            .toList());
        errors.sort(BY_POSITION);

        Map<String, List<EventEvaluation>> byKind = new LinkedHashMap<>();
        for (EventEvaluation error : errors) {
            byKind.computeIfAbsent(kind(error), k -> new ArrayList<>()).add(error);
        }
        List<ErrorSample> kinds = new ArrayList<>();
        byKind.forEach((key, members) -> kinds.add(new ErrorSample(members.get(0), members.size())));
        kinds.sort(Comparator.comparingInt(ErrorSample::events).reversed().thenComparing(s -> s.event(), BY_POSITION));
        return kinds;
    }

    /** A random sample of {@code size} kinds of mistake (all of them if there are fewer), in dataset order. */
    public static List<ErrorSample> sample(List<EventEvaluation> events, int size, long seed) {
        List<ErrorSample> kinds = new ArrayList<>(errorTypes(events));
        kinds.sort(Comparator.comparing(ErrorSample::event, BY_POSITION));
        Collections.shuffle(kinds, new Random(seed));
        List<ErrorSample> picked = new ArrayList<>(kinds.subList(0, Math.min(size, kinds.size())));
        picked.sort(Comparator.comparing(ErrorSample::event, BY_POSITION));
        return picked;
    }

    private static String kind(EventEvaluation error) {
        String predicted = error.covered() && error.predictedStatementId() != null ? error.predictedStatementId() : "-";
        return predicted + "|" + error.truthKey();
    }
}
