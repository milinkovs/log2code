package org.log2code.eval.truth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.log2code.eval.TestData.PROJECT_UNIT;
import static org.log2code.eval.TestData.dependency;
import static org.log2code.eval.TestData.dependencyStatement;
import static org.log2code.eval.TestData.label;
import static org.log2code.eval.TestData.log;
import static org.log2code.eval.TestData.match;
import static org.log2code.eval.TestData.module;
import static org.log2code.eval.TestData.projectStatement;
import static org.log2code.eval.TestData.reliable;
import static org.log2code.eval.TestData.run;
import static org.log2code.eval.TestData.statement;
import static org.log2code.eval.TestData.unreliable;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Label;

class GroundTruthResolverTest {

    private static final CodeUnit LIB_A = dependency("lib:a", "1.0");
    private static final CodeUnit LIB_B = dependency("lib:b", "1.0");

    // customers: Owner.save lines 10-12 (s1), Owner.audit lines 12-14 (s6, overlaps s1 on line 12), Owner.Inner line 30 (s2),
    // an unsupported-template statement on line 20 (s7); vets: Vet line 5 (s3); lib:a (selected by customers only): Util 7-8 (s4);
    // lib:b is selected by nobody (s5).
    private static final CatalogEntry S1 = projectStatement("s1", "customers", "org.x.Owner", "org.x.Owner", "save", 10, 12);
    private static final CatalogEntry S2 = projectStatement("s2", "customers", "org.x.Owner$Inner", "org.x.Owner.Inner", "run", 30, 30);
    private static final CatalogEntry S3 = projectStatement("s3", "vets", "org.x.Vet", "org.x.Vet", "list", 5, 5);
    private static final CatalogEntry S4 = dependencyStatement("s4", LIB_A, "lib.Util", "go", 7, 8);
    private static final CatalogEntry S5 = dependencyStatement("s5", LIB_B, "lib.Other", "go", 3, 3);
    private static final CatalogEntry S6 = projectStatement("s6", "customers", "org.x.Owner", "org.x.Owner", "audit", 12, 14);
    private static final CatalogEntry S7 = statement("s7", PROJECT_UNIT, "customers", "org.x.Owner",
        "org.x.Owner", "raw", 20, 20, "unsupported", "unsupported");

    private final CatalogView catalog = CatalogView.build(
        run(module("customers", "lib:a:1.0"), module("vets")), List.of(S1, S2, S3, S4, S5, S6, S7));
    private final GroundTruthResolver resolver = new GroundTruthResolver(catalog);

    private TruthResult oracle(String service, String className, int line) {
        return resolver.resolve(log("e", service, reliable(className, line), match("unmatched", null, null)), null);
    }

    @Test
    void oracleLineInsideTheCallRangeSelectsThatStatement() {
        assertThat(oracle("customers", "org.x.Owner", 10).statementIds()).containsExactly("s1");
        assertThat(oracle("customers", "org.x.Owner", 11).statementIds()).containsExactly("s1");
        assertThat(oracle("customers", "org.x.Owner", 10).source()).isEqualTo(TruthSource.ORACLE);
    }

    @Test
    void statementsSharingALineAreAllCorrect() {
        TruthResult result = oracle("customers", "org.x.Owner", 12);
        assertThat(result.statementIds()).containsExactly("s1", "s6");
        assertThat(result.key()).isEqualTo("s1+s6");
    }

    @Test
    void lineOutsideEveryRangeIsNotInCatalog() {
        TruthResult result = oracle("customers", "org.x.Owner", 15);
        assertThat(result.notInCatalog()).isTrue();
        assertThat(result.evaluable()).isFalse();
        assertThat(result.known()).isTrue();
    }

    @Test
    void unknownClassIsNotInCatalog() {
        assertThat(oracle("customers", "org.x.Missing", 10).notInCatalog()).isTrue();
    }

    @Test
    void nestedClassMatchesByBinaryNameAndByDottedFqn() {
        assertThat(oracle("customers", "org.x.Owner$Inner", 30).statementIds()).containsExactly("s2");
        // a catalog entry that only knows the dotted name still matches the oracle's binary name
        CatalogEntry fqnOnly = projectStatement("s8", "customers", null, "org.x.Owner.Other", "run", 40, 40);
        CatalogView view = CatalogView.build(run(module("customers")), List.of(fqnOnly));
        TruthResult result = new GroundTruthResolver(view)
            .resolve(log("e", "customers", reliable("org.x.Owner$Other", 40), match("unmatched", null, null)), null);
        assertThat(result.statementIds()).containsExactly("s8");
    }

    @Test
    void methodNameIsNotCompared() {
        // the oracle reports lambda$save$0 for a log call inside a lambda of save(); the catalog says save
        TruthResult result = resolver.resolve(log("e", "customers",
            new GroundTruth("org.x.Owner", "lambda$save$0", 11, true), match("unmatched", null, null)), null);
        assertThat(result.statementIds()).containsExactly("s1");
    }

    @Test
    void onlyStatementsApplicableToTheServiceCount() {
        // Owner belongs to the customers module, not to vets
        assertThat(oracle("vets", "org.x.Owner", 10).notInCatalog()).isTrue();
        assertThat(oracle("vets", "org.x.Vet", 5).statementIds()).containsExactly("s3");
        // lib:a is selected by customers only; lib:b by nobody
        assertThat(oracle("customers", "lib.Util", 7).statementIds()).containsExactly("s4");
        assertThat(oracle("vets", "lib.Util", 7).notInCatalog()).isTrue();
        assertThat(oracle("customers", "lib.Other", 3).notInCatalog()).isTrue();
    }

    @Test
    void aServiceWithoutAModuleHasNoApplicableStatements() {
        assertThat(oracle("gateway", "org.x.Owner", 10).notInCatalog()).isTrue();
    }

    @Test
    void unsupportedStatementsStayInTheTruthEvenThoughTheMatcherCannotPickThem() {
        assertThat(oracle("customers", "org.x.Owner", 20).statementIds()).containsExactly("s7");
    }

    @Test
    void unreliableOrMissingOracleGivesNoTruth() {
        assertThat(resolver.resolve(log("e", "customers", unreliable("org.x.Owner", 10), null), null)).isEqualTo(TruthResult.NONE);
        assertThat(resolver.resolve(log("e", "customers", null, null), null)).isEqualTo(TruthResult.NONE);
        assertThat(resolver.resolve(log("e", "customers", new GroundTruth("org.x.Owner", "m", null, true), null), null))
            .isEqualTo(TruthResult.NONE);
        assertThat(TruthResult.NONE.known()).isFalse();
    }

    @Test
    void aCorrectLabelMakesTheLabelledPredictionTheTruthAndBeatsTheOracle() {
        EnrichedLog log = log("e", "customers", reliable("org.x.Owner", 10), match("matched", "s6", "high", "s6"));
        Label label = label("e", Label.VERDICT_CORRECT, null, "s2");

        TruthResult result = resolver.resolve(log, label);

        assertThat(result.source()).isEqualTo(TruthSource.MANUAL);
        assertThat(result.statementIds()).containsExactly("s2");
    }

    @Test
    void aCorrectLabelWithoutSnapshotFallsBackToTheCurrentPrediction() {
        EnrichedLog log = log("e", "customers", null, match("matched", "s6", "high", "s6"));

        TruthResult result = resolver.resolve(log, label("e", Label.VERDICT_CORRECT, null, null));

        assertThat(result.statementIds()).containsExactly("s6");
    }

    @Test
    void anIncorrectLabelUsesTheCorrectStatement() {
        EnrichedLog log = log("e", "customers", null, match("matched", "s1", "high", "s1"));

        TruthResult result = resolver.resolve(log, label("e", Label.VERDICT_INCORRECT, "s6", "s1"));

        assertThat(result.source()).isEqualTo(TruthSource.MANUAL);
        assertThat(result.statementIds()).containsExactly("s6");
    }

    @Test
    void anIncorrectLabelWithoutACorrectStatementFallsBackToTheOracle() {
        EnrichedLog log = log("e", "customers", reliable("org.x.Owner", 10), match("matched", "s6", "high", "s6"));

        TruthResult result = resolver.resolve(log, label("e", Label.VERDICT_INCORRECT, null, "s6"));

        assertThat(result.source()).isEqualTo(TruthSource.ORACLE);
        assertThat(result.statementIds()).containsExactly("s1");
    }

    @Test
    void aNotInCatalogLabelBeatsAnOracleThatPointsAtAStatement() {
        EnrichedLog log = log("e", "customers", reliable("org.x.Owner", 10), match("matched", "s1", "high", "s1"));

        TruthResult result = resolver.resolve(log, label("e", Label.VERDICT_NOT_IN_CATALOG, null, "s1"));

        assertThat(result.source()).isEqualTo(TruthSource.MANUAL);
        assertThat(result.notInCatalog()).isTrue();
    }

    @Test
    void unknownVerdictIsIgnored() {
        EnrichedLog log = log("e", "customers", reliable("org.x.Owner", 10), match("unmatched", null, null));

        TruthResult result = resolver.resolve(log, label("e", "bogus", "s6", null));

        assertThat(result.source()).isEqualTo(TruthSource.ORACLE);
    }

    @Test
    void catalogViewLooksStatementsUpById() {
        assertThat(catalog.byId("s4")).contains(S4);
        assertThat(catalog.byId("nope")).isEmpty();
        assertThat(catalog.size()).isEqualTo(7);
    }
}
