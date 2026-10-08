package org.log2code.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.log2code.eval.TestData.log;
import static org.log2code.eval.TestData.match;
import static org.log2code.eval.TestData.module;
import static org.log2code.eval.TestData.projectStatement;
import static org.log2code.eval.TestData.reliable;
import static org.log2code.eval.TestData.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.EnrichedLog;
import org.log2code.eval.EvalResult;
import org.log2code.eval.EvalRunner;
import org.log2code.eval.TestData.InMemorySource;
import org.log2code.eval.metrics.EventEvaluation;
import org.log2code.eval.truth.CatalogView;

class ErrorSamplerTest {

    private static final int STATEMENTS = 30;

    private final List<CatalogEntry> statements = new ArrayList<>();
    private final List<EnrichedLog> logs = new ArrayList<>();

    /**
     * 30 kinds of mistake with one event each (the event whose truth is statement i is predicted as statement i+1),
     * plus 100 more events repeating the mistake of statement 0, plus 5 correct events.
     */
    private List<EventEvaluation> events() throws Exception {
        for (int i = 0; i < STATEMENTS; i++) {
            statements.add(projectStatement("s" + i, "svc", "p.C", "p.C", "m", 10 * (i + 1), 10 * (i + 1)));
        }
        for (int i = 0; i < STATEMENTS; i++) {
            logs.add(wrong("w" + i, i, (i + 1) % STATEMENTS));
        }
        for (int n = 0; n < 100; n++) {
            logs.add(wrong("rep" + n, 0, 1));
        }
        for (int i = 0; i < 5; i++) {
            logs.add(log("ok" + i, "svc", reliable("p.C", 10 * (i + 1)), match("matched", "s" + i, "high", "s" + i)));
        }
        CatalogView view = CatalogView.build(run(module("svc")), statements);
        EvalResult result = EvalRunner.run(new InMemorySource(logs, Map.of(), view), "ds", 25, 42);
        return result.events();
    }

    private static EnrichedLog wrong(String id, int truth, int predicted) {
        return log(id, "svc", reliable("p.C", 10 * (truth + 1)), match("matched", "s" + predicted, "high", "s" + predicted));
    }

    @Test
    void errorKindsAreCountedAndOrderedByFrequency() throws Exception {
        List<ErrorSample> kinds = ErrorSampler.errorTypes(events());

        assertThat(kinds).hasSize(STATEMENTS);                       // the repeated mistake is the same kind as w0
        assertThat(kinds.get(0).events()).isEqualTo(101);            // w0 plus the 100 repeats
        assertThat(kinds.subList(1, kinds.size())).allSatisfy(kind -> assertThat(kind.events()).isEqualTo(1));
    }

    @Test
    void theSampleHasOneExamplePerKindAndTheRequestedSize() throws Exception {
        List<ErrorSample> sample = ErrorSampler.sample(events(), 25, 42);

        assertThat(sample).hasSize(25);
        assertThat(sample).extracting(s -> s.event().truthKey() + "|" + s.event().predictedStatementId()).doesNotHaveDuplicates();
        assertThat(sample).allSatisfy(s -> assertThat(s.event().correctAt1()).isFalse());
    }

    @Test
    void theSampleIsReproducibleForTheSameSeedAndInputOrder() throws Exception {
        List<EventEvaluation> events = events();
        List<EventEvaluation> shuffled = new ArrayList<>(events);
        Collections.shuffle(shuffled, new Random(7));

        assertThat(ErrorSampler.sample(events, 25, 42)).isEqualTo(ErrorSampler.sample(events, 25, 42));
        assertThat(ErrorSampler.sample(shuffled, 25, 42)).isEqualTo(ErrorSampler.sample(events, 25, 42));
        assertThat(ErrorSampler.sample(events, 25, 43)).isNotEqualTo(ErrorSampler.sample(events, 25, 42));
    }

    @Test
    void fewerKindsThanTheSampleSizeGivesAllOfThem() throws Exception {
        assertThat(ErrorSampler.sample(events(), 100, 42)).hasSize(STATEMENTS);
    }

    @Test
    void noErrorsGivesAnEmptySample() {
        assertThat(ErrorSampler.sample(List.of(), 25, 42)).isEmpty();
        assertThat(ErrorSampler.errorTypes(List.of())).isEmpty();
    }
}
