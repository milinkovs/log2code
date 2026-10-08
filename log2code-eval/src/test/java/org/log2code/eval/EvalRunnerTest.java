package org.log2code.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.log2code.eval.TestData.dependency;
import static org.log2code.eval.TestData.dependencyStatement;
import static org.log2code.eval.TestData.log;
import static org.log2code.eval.TestData.match;
import static org.log2code.eval.TestData.module;
import static org.log2code.eval.TestData.projectStatement;
import static org.log2code.eval.TestData.reliable;
import static org.log2code.eval.TestData.run;
import static org.log2code.eval.TestData.statement;
import static org.log2code.eval.TestData.unreliable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.EnrichedLog;
import org.log2code.eval.TestData.InMemorySource;
import org.log2code.eval.metrics.EvalMetrics;
import org.log2code.eval.metrics.EvalMetrics.BreakdownRow;
import org.log2code.eval.metrics.EvalMetrics.GroupRow;
import org.log2code.eval.report.ErrorSampler;
import org.log2code.eval.metrics.StatementRow;
import org.log2code.eval.truth.CatalogView;

/**
 * T33 test: metrics on a synthetic dataset with hand-computed results. Ten events have a ground truth in the
 * catalog (the denominator of accuracy), one has none and one points outside the catalog; every expected
 * number below is worked out in the comments.
 *
 * <pre>
 * event  truth  status     top-1  candidates (best first)    @1  @3
 * E1     a      matched/high      a      a b c                      yes yes
 * E2     a      matched/high      a      a                          yes yes
 * E3     b      matched/high      c      c b a                      no  yes (rank 2)
 * E4     c      matched/medium    c      c                          yes yes
 * E5     d      ambiguous/low     e      e f d                      no  yes (rank 3)
 * E6     e      ambiguous/low     e      e d                        yes yes
 * E7     f      unmatched         -      d f                        no  yes (rank 2, kept for unmatched)
 * E8     a      unmatched         -      b c d e f                  no  no
 * E9     b      matched/low       a      a c d e b                  no  no  (rank 5)
 * E10    x1     matched/high      x1     x1                         yes yes (dependency)
 * E11    -      matched/high      a      a                          (no ground truth: coverage only)
 * E12    gone   matched/medium    a      a                          (ground truth outside the catalog)
 * </pre>
 */
class EvalRunnerTest {

    private static final CodeUnit LIB_X = dependency("lib:x", "1");
    private static final String CLASS = "p.C";

    private static final CatalogEntry A = projectStatement("a", "svc", CLASS, CLASS, "m", 10, 10);
    private static final CatalogEntry B = projectStatement("b", "svc", CLASS, CLASS, "m", 20, 20);
    private static final CatalogEntry C = projectStatement("c", "svc", CLASS, CLASS, "m", 30, 30);
    private static final CatalogEntry D = projectStatement("d", "svc", CLASS, CLASS, "m", 40, 40);
    private static final CatalogEntry E = projectStatement("e", "svc", CLASS, CLASS, "m", 50, 50);
    private static final CatalogEntry F = projectStatement("f", "svc", CLASS, CLASS, "m", 60, 60);
    private static final CatalogEntry G = statement("g", TestData.PROJECT_UNIT, "svc", CLASS, CLASS, "m", 70, 70,
        "unsupported", "unsupported");
    private static final CatalogEntry X1 = dependencyStatement("x1", LIB_X, "lib.X", "go", 5, 5);

    private final CatalogView catalog = CatalogView.build(run(module("svc", "lib:x:1")), List.of(A, B, C, D, E, F, G, X1));

    private List<EnrichedLog> tenEvaluableEventsPlusTwo() {
        List<EnrichedLog> logs = new ArrayList<>();
        logs.add(log("E1", "svc", reliable(CLASS, 10), match("matched", "a", "high", "a", "b", "c")));
        logs.add(log("E2", "svc", reliable(CLASS, 10), match("matched", "a", "high", "a")));
        logs.add(log("E3", "svc", reliable(CLASS, 20), match("matched", "c", "high", "c", "b", "a")));
        logs.add(log("E4", "svc", reliable(CLASS, 30), match("matched", "c", "medium", "c")));
        logs.add(log("E5", "svc", reliable(CLASS, 40), match("ambiguous", "e", "low", "e", "f", "d")));
        logs.add(log("E6", "svc", reliable(CLASS, 50), match("ambiguous", "e", "low", "e", "d")));
        logs.add(log("E7", "svc", reliable(CLASS, 60), match("unmatched", null, null, "d", "f")));
        logs.add(log("E8", "svc", reliable(CLASS, 10), match("unmatched", null, null, "b", "c", "d", "e", "f")));
        logs.add(log("E9", "svc", reliable(CLASS, 20), match("matched", "a", "low", "a", "c", "d", "e", "b")));
        logs.add(log("E10", "svc", reliable("lib.X", 5), match("matched", "x1", "high", "x1")));
        logs.add(log("E11", "svc", unreliable(CLASS, 10), match("matched", "a", "high", "a")));
        logs.add(log("E12", "svc", reliable(CLASS, 999), match("matched", "a", "medium", "a")));
        return logs;
    }

    private EvalResult evaluate(List<EnrichedLog> logs) throws Exception {
        return EvalRunner.run(new InMemorySource(logs, Map.of(), catalog), "ds", 25, 42);
    }

    @Test
    void countsSplitEventsBySourceOfTruthAndStatus() throws Exception {
        EvalMetrics.Counts counts = evaluate(tenEvaluableEventsPlusTwo()).metrics().counts();

        assertThat(counts.totalEvents()).isEqualTo(12);
        assertThat(counts.matched()).isEqualTo(8);      // E1 E2 E3 E4 E9 E10 E11 E12
        assertThat(counts.ambiguous()).isEqualTo(2);    // E5 E6
        assertThat(counts.unmatched()).isEqualTo(2);    // E7 E8
        assertThat(counts.truthOracle()).isEqualTo(11); // E1..E10 and E12
        assertThat(counts.truthManual()).isZero();
        assertThat(counts.truthNone()).isEqualTo(1);    // E11
        assertThat(counts.truthNotInCatalog()).isEqualTo(1); // E12
        assertThat(counts.evaluable()).isEqualTo(10);
        assertThat(counts.evaluableUnsupported()).isZero();
    }

    @Test
    void headlineMetricsMatchTheHandComputedValues() throws Exception {
        EvalMetrics.Headline h = evaluate(tenEvaluableEventsPlusTwo()).metrics().headline();

        // coverage: matched or ambiguous = 10 of 12 events
        assertThat(h.coverage()).isCloseTo(10.0 / 12, within(1e-9));
        // events with a truth: 11 (E11 has none); of those E7 and E8 are unmatched, so 9 are covered
        assertThat(h.coverageWithTruth()).isCloseTo(9.0 / 11, within(1e-9));
        // accuracy@1: E1 E2 E4 E6 E10 are right = 5 of 10
        assertThat(h.accuracyAt1()).isCloseTo(0.5, within(1e-9));
        // accuracy@3: also E3 (rank 2), E5 (rank 3) and E7 (rank 2, unmatched keeps its candidates) = 8 of 10
        assertThat(h.accuracyAt3()).isCloseTo(0.8, within(1e-9));
        // covered and evaluable: E1 E2 E3 E4 E5 E6 E9 E10 = 8; among them @3 is right for all but E9 = 7
        assertThat(h.accuracyAt3Covered()).isCloseTo(7.0 / 8, within(1e-9));
        assertThat(h.precisionCovered()).isCloseTo(5.0 / 8, within(1e-9));
        assertThat(h.accuracyAt1Supported()).isCloseTo(0.5, within(1e-9));
        // ambiguous: E5 E6 = 2 of 12 events, 2 of 10 evaluable
        assertThat(h.ambiguousShare()).isCloseTo(2.0 / 12, within(1e-9));
        assertThat(h.ambiguousShareEvaluable()).isCloseTo(0.2, within(1e-9));
        // E12 is the only truth outside the catalog (1 of 11), and it got a prediction anyway
        assertThat(h.notInCatalogShare()).isCloseTo(1.0 / 11, within(1e-9));
        assertThat(h.falseMatchOnNotInCatalog()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void precisionPerConfidenceLevelShowsCalibration() throws Exception {
        List<GroupRow> rows = evaluate(tenEvaluableEventsPlusTwo()).metrics().byConfidenceLevel();

        // high: E1 E2 E3 E10, right at @1 for E1 E2 E10; medium: E4; low: E5 E6 E9, right at @1 only for E6
        assertThat(rows).extracting(GroupRow::key).containsExactly("high", "medium", "low");
        assertThat(rows).extracting(GroupRow::events).containsExactly(4, 1, 3);
        assertThat(rows).extracting(GroupRow::correctAt1).containsExactly(3, 1, 1);
        assertThat(rows).extracting(GroupRow::correctAt3).containsExactly(4, 1, 2);
        assertThat(rows.get(0).accuracyAt1()).isCloseTo(0.75, within(1e-9));
        assertThat(rows.get(2).accuracyAt1()).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void resultsPerStatusIncludeUnmatchedAsMisses() throws Exception {
        List<GroupRow> rows = evaluate(tenEvaluableEventsPlusTwo()).metrics().byStatus();

        assertThat(rows).extracting(GroupRow::key).containsExactly("matched", "ambiguous", "unmatched");
        assertThat(rows).extracting(GroupRow::events).containsExactly(6, 2, 2);    // E1 E2 E3 E4 E9 E10 | E5 E6 | E7 E8
        assertThat(rows).extracting(GroupRow::correctAt1).containsExactly(4, 1, 0);
        assertThat(rows).extracting(GroupRow::correctAt3).containsExactly(5, 2, 1);
    }

    @Test
    void uniqueStatementMetricsCountEachStatementOnce() throws Exception {
        EvalMetrics.UniqueStatements u = evaluate(tenEvaluableEventsPlusTwo()).metrics().uniqueStatements();

        // statements and their events: a=E1 E2 E8 (2 right), b=E3 E9 (0), c=E4 (1), d=E5 (0), e=E6 (1), f=E7 (0), x1=E10 (1)
        assertThat(u.statements()).isEqualTo(7);
        assertThat(u.hitAtLeastOnce()).isEqualTo(4);   // a c e x1
        assertThat(u.hitRate()).isCloseTo(4.0 / 7, within(1e-9));
        // macro @1: (2/3 + 0 + 1 + 0 + 1 + 0 + 1) / 7
        assertThat(u.macroAccuracyAt1()).isCloseTo((2.0 / 3 + 3) / 7, within(1e-9));
        // macro @3: a 2/3, b 1/2, c 1, d 1, e 1, f 1, x1 1
        assertThat(u.macroAccuracyAt3()).isCloseTo((2.0 / 3 + 0.5 + 5) / 7, within(1e-9));
        assertThat(u.topStatementShare()).isCloseTo(0.3, within(1e-9));   // a has 3 of 10 events
    }

    @Test
    void breakdownsGroupByTheCorrectStatementsAttributes() throws Exception {
        Map<String, List<BreakdownRow>> breakdowns = evaluate(tenEvaluableEventsPlusTwo()).metrics().breakdowns();

        List<BreakdownRow> types = breakdowns.get("code_unit_type");
        assertThat(types).extracting(BreakdownRow::key).containsExactly("project", "dependency");
        BreakdownRow project = types.get(0);
        assertThat(project.events()).isEqualTo(9);          // E1..E9
        assertThat(project.covered()).isEqualTo(7);         // all but the unmatched E7 and E8
        assertThat(project.correctAt1()).isEqualTo(4);
        assertThat(project.correctAt3()).isEqualTo(7);
        assertThat(project.uniqueStatements()).isEqualTo(6);
        assertThat(project.uniqueStatementsHit()).isEqualTo(3);   // a c e
        BreakdownRow dependency = types.get(1);
        assertThat(dependency.events()).isEqualTo(1);
        assertThat(dependency.accuracyAt1()).isCloseTo(1.0, within(1e-9));

        assertThat(breakdowns.get("artifact")).extracting(BreakdownRow::key).containsExactly("petclinic", "lib:x");
        assertThat(breakdowns.get("template_kind")).extracting(BreakdownRow::key).containsExactly("placeholders");
        assertThat(breakdowns.get("service")).extracting(BreakdownRow::events).containsExactly(10);
    }

    @Test
    void onlyTheFifteenBiggestArtifactsAreListedAndTheRestIsFolded() throws Exception {
        List<CatalogEntry> entries = new ArrayList<>();
        List<EnrichedLog> logs = new ArrayList<>();
        List<String> gavs = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            CodeUnit unit = dependency("lib:art" + i, "1");
            entries.add(dependencyStatement("s" + i, unit, "lib.C" + i, "m", 10, 10));
            gavs.add("lib:art" + i + ":1");
            // artifact i gets 17 - i events, so the order of artifacts is art0, art1, ...
            for (int n = 0; n < 17 - i; n++) {
                logs.add(log("e" + i + "-" + n, "svc", reliable("lib.C" + i, 10), match("matched", "s" + i, "high", "s" + i)));
            }
        }
        CatalogView view = CatalogView.build(run(module("svc", gavs.toArray(String[]::new))), entries);

        List<BreakdownRow> artifacts = EvalRunner.run(new InMemorySource(logs, Map.of(), view), "ds", 25, 42)
            .metrics().breakdowns().get("artifact");

        assertThat(artifacts).hasSize(16);
        assertThat(artifacts.get(0).key()).isEqualTo("lib:art0");
        assertThat(artifacts.get(14).key()).isEqualTo("lib:art14");
        BreakdownRow other = artifacts.get(15);
        assertThat(other.key()).isEqualTo("(other artifacts)");
        assertThat(other.events()).isEqualTo(2 + 1);        // art15 has 2 events, art16 has 1
        assertThat(other.uniqueStatements()).isEqualTo(2);
    }

    @Test
    void anUnsupportedStatementIsEvaluableAndAlwaysAMiss() throws Exception {
        List<EnrichedLog> logs = new ArrayList<>(tenEvaluableEventsPlusTwo());
        logs.add(log("E13", "svc", reliable(CLASS, 70), match("unmatched", null, null)));

        EvalMetrics metrics = evaluate(logs).metrics();

        assertThat(metrics.counts().evaluable()).isEqualTo(11);
        assertThat(metrics.counts().evaluableUnsupported()).isEqualTo(1);
        assertThat(metrics.headline().accuracyAt1()).isCloseTo(5.0 / 11, within(1e-9));
        // without the unsupported statement the accuracy is what it was before
        assertThat(metrics.headline().accuracyAt1Supported()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void statementRowsListEachStatementWithItsMostFrequentPrediction() throws Exception {
        List<StatementRow> rows = evaluate(tenEvaluableEventsPlusTwo()).statements();

        assertThat(rows).extracting(StatementRow::key).containsExactly("a", "b", "c", "d", "e", "f", "x1");
        StatementRow a = rows.get(0);
        assertThat(a.events()).isEqualTo(3);
        assertThat(a.covered()).isEqualTo(2);
        assertThat(a.correctAt1()).isEqualTo(2);
        assertThat(a.topPredictionId()).isEqualTo("a");
        assertThat(a.topPredictionCount()).isEqualTo(2);
        assertThat(a.accuracyAt1()).isCloseTo(2.0 / 3, within(1e-9));
        StatementRow f = rows.get(5);
        assertThat(f.topPredictionId()).isNull();           // the only event is unmatched: no prediction
        assertThat(f.correctAt3()).isEqualTo(1);
    }

    @Test
    void errorsAreGroupedByKindAndSampledReproducibly() throws Exception {
        EvalResult result = evaluate(tenEvaluableEventsPlusTwo());

        // wrong top-1: E3 (c for b), E5 (e for d), E7 (nothing for f), E8 (nothing for a), E9 (a for b) = 5 kinds
        assertThat(result.errorTypes()).hasSize(5);
        assertThat(result.errorTypes()).allSatisfy(kind -> assertThat(kind.events()).isEqualTo(1));
        assertThat(result.errorSamples()).hasSize(5);   // fewer kinds than the sample size: all of them
        assertThat(ErrorSampler.sample(result.events(), 25, 42)).isEqualTo(result.errorSamples());
    }

    @Test
    void aDatasetWithoutEventsIsAUserError() {
        assertThatThrownBy(() -> EvalRunner.run(new InMemorySource(List.of(), Map.of(), catalog), "empty", 25, 42))
            .isInstanceOf(EvalUserException.class)
            .hasMessageContaining("empty")
            .hasMessageContaining("ingest");
    }

    @Test
    void aDatasetWithTwoCodeVersionsIsAUserError() {
        EnrichedLog other = new EnrichedLog("x", null, null, "ds", "f", 1, 1, 0, "svc", "m", null, null, null,
            org.log2code.core.model.Level.INFO, "l", null, "m", "r", null, null, null,
            new org.log2code.core.model.CodeVersion(TestData.PROJECT, "v2"), null, null, "p", "t", null);
        List<EnrichedLog> logs = List.of(log("E1", "svc", null, null), other);

        assertThatThrownBy(() -> EvalRunner.run(new InMemorySource(logs, Map.of(), catalog), "ds", 25, 42))
            .isInstanceOf(EvalUserException.class)
            .hasMessageContaining("code version");
    }

    @Test
    void aDatasetWithoutGroundTruthStillReportsCoverage() throws Exception {
        List<EnrichedLog> logs = List.of(
            log("n1", "svc", null, match("matched", "a", "high", "a")),
            log("n2", "svc", null, match("unmatched", null, null)));

        EvalMetrics metrics = evaluate(logs).metrics();

        assertThat(metrics.headline().coverage()).isCloseTo(0.5, within(1e-9));
        assertThat(metrics.headline().accuracyAt1()).isNull();
        assertThat(metrics.counts().evaluable()).isZero();
        assertThat(metrics.uniqueStatements().statements()).isZero();
    }
}
