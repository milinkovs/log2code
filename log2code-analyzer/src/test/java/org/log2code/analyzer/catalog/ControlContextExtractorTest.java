package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EarlyExit;
import org.log2code.core.model.PrecedingStatement;

/**
 * T11: level-2 control context. Uses a marker call {@code marker()} in place of a real log call, like
 * {@link EnclosingBlockResolverTest}/{@link MethodContextResolverTest} - {@link ControlContextExtractor}
 * only needs any {@link Node} plus the {@link MethodContext} boundary it already shares with those two
 * resolvers. See {@code ControlContextGoldenTest} for the required fixture-method/golden-JSON coverage
 * (nested if/else, guard, catch, loop+break, switch, lambda, log at method start).
 */
class ControlContextExtractorTest {

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private static ControlContext extract(String methodBody) {
        return extract(methodBody, 10);
    }

    private static ControlContext extract(String methodBody, int maxPreceding) {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Fixture {
                void run(int id, Object x) {
                    %s
                }
            }
            """.formatted(methodBody));
        Node node = unit.findAll(MethodCallExpr.class).stream()
            .filter(call -> call.getNameAsString().equals("marker"))
            .<Node>map(call -> call)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no marker() call found in:\n" + methodBody));
        MethodContext methodContext = MethodContextResolver.resolve(node);
        return ControlContextExtractor.extract(node, methodContext, maxPreceding);
    }

    // --- conditions[] --------------------------------------------------------------------------

    @Test
    void directlyInMethodBodyHasNoConditions() {
        ControlContext control = extract("marker();");
        assertThat(control.conditions()).isEmpty();
        assertThat(control.earlyExits()).isEmpty();
        assertThat(control.preceding()).isEmpty();
        assertThat(control.callsBefore()).isEmpty();
    }

    @Test
    void ifThen() {
        ControlContext control = extract("""
            if (id > 0) {
                marker();
            }
            """);
        assertThat(control.conditions()).containsExactly(new Condition("if", "id > 0", 3, false));
    }

    @Test
    void ifElse() {
        ControlContext control = extract("""
            if (id > 0) {
                other();
            } else {
                marker();
            }
            """);
        assertThat(control.conditions()).hasSize(1);
        Condition c = control.conditions().get(0);
        assertThat(c.kind()).isEqualTo("else");
        assertThat(c.text()).isEqualTo("id > 0");
        assertThat(c.negated()).isTrue();
    }

    @Test
    void elseIfChainIsOuterElseThenInnerIf() {
        ControlContext control = extract("""
            if (id == 1) {
                other();
            } else if (id == 2) {
                marker();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text, Condition::negated)
            .containsExactly(
                tuple("else", "id == 1", true),
                tuple("if", "id == 2", false));
    }

    @Test
    void nestedIfBothThenIsOutsideIn() {
        ControlContext control = extract("""
            if (id > 0) {
                if (id > 10) {
                    marker();
                }
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("if", "id > 0"), tuple("if", "id > 10"));
    }

    @Test
    void ifWithoutBracesStillResolves() {
        ControlContext control = extract("if (id > 0) marker();");
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("if", "id > 0"));
    }

    @Test
    void forLoopUsesCompareExpressionAsText() {
        ControlContext control = extract("""
            for (int i = 0; i < id; i++) {
                marker();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("loop", "i < id"));
    }

    @Test
    void foreachLoopUsesVariableAndIterableAsText() {
        ControlContext control = extract("""
            for (int i : new int[] {1, 2}) {
                marker();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("loop", "int i : new int[] { 1, 2 }"));
    }

    @Test
    void whileLoopUsesConditionAsText() {
        ControlContext control = extract("""
            while (id > 0) {
                marker();
                id--;
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("loop", "id > 0"));
    }

    @Test
    void doWhileLoopUsesConditionAsText() {
        ControlContext control = extract("""
            do {
                marker();
            } while (id > 0);
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("loop", "id > 0"));
    }

    @Test
    void switchCaseUsesSelectorEqualsLabel() {
        ControlContext control = extract("""
            switch (id) {
                case 1 -> marker();
                default -> other();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("switch_case", "id == 1"));
    }

    @Test
    void switchDefaultCaseUsesDefaultLabel() {
        ControlContext control = extract("""
            switch (id) {
                case 1 -> other();
                default -> marker();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("switch_case", "id == default"));
    }

    @Test
    void multiLabelSwitchCase() {
        ControlContext control = extract("""
            switch (id) {
                case 1, 2 -> marker();
                default -> other();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::text)
            .containsExactly("id == 1, 2");
    }

    @Test
    void catchReportsExceptionType() {
        ControlContext control = extract("""
            try {
                risky();
            } catch (IllegalStateException e) {
                marker();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("catch", "IllegalStateException"));
    }

    @Test
    void multiCatchReportsUnionType() {
        ControlContext control = extract("""
            try {
                risky();
            } catch (IllegalStateException | IllegalArgumentException e) {
                marker();
            }
            """);
        assertThat(control.conditions().get(0).text())
            .contains("IllegalStateException").contains("IllegalArgumentException");
    }

    @Test
    void tryBlockHasEmptyText() {
        ControlContext control = extract("""
            try {
                marker();
            } catch (RuntimeException e) {
                throw e;
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("try", ""));
    }

    @Test
    void finallyBlockHasEmptyText() {
        ControlContext control = extract("""
            try {
                risky();
            } finally {
                marker();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("finally", ""));
    }

    @Test
    void synchronizedBlockUsesLockExpressionAsText() {
        ControlContext control = extract("""
            synchronized (this) {
                marker();
            }
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("synchronized", "this"));
    }

    @Test
    void lambdaUsesJoinedParameterNamesAsText() {
        ControlContext control = extract("""
            java.util.function.Consumer<String> c = owner -> {
                marker();
            };
            c.accept("x");
            """);
        assertThat(control.conditions()).extracting(Condition::kind, Condition::text)
            .containsExactly(tuple("lambda", "owner"));
    }

    // --- early_exits[] ---------------------------------------------------------------------------

    @Test
    void guardWithBareReturnIsEarlyExit() {
        ControlContext control = extract("""
            if (id == 0) {
                return;
            }
            marker();
            """);
        assertThat(control.earlyExits()).containsExactly(new EarlyExit("id == 0", 3, "return"));
    }

    @Test
    void guardWithoutBracesIsEarlyExit() {
        ControlContext control = extract("""
            if (id == 0) return;
            marker();
            """);
        assertThat(control.earlyExits()).extracting(EarlyExit::exitKind).containsExactly("return");
    }

    @Test
    void guardWithThrowIsEarlyExit() {
        ControlContext control = extract("""
            if (id == 0) {
                throw new IllegalArgumentException();
            }
            marker();
            """);
        assertThat(control.earlyExits()).extracting(EarlyExit::exitKind).containsExactly("throw");
    }

    @Test
    void guardWithContinueInLoopIsEarlyExit() {
        ControlContext control = extract("""
            for (int i = 0; i < id; i++) {
                if (i == 0) {
                    continue;
                }
                marker();
            }
            """);
        assertThat(control.earlyExits()).extracting(EarlyExit::exitKind).containsExactly("continue");
    }

    @Test
    void guardWithBreakInLoopIsEarlyExit() {
        ControlContext control = extract("""
            for (int i = 0; i < id; i++) {
                if (i == 0) {
                    break;
                }
                marker();
            }
            """);
        assertThat(control.earlyExits()).extracting(EarlyExit::exitKind).containsExactly("break");
    }

    @Test
    void guardWithElseIsNotAnEarlyExit() {
        ControlContext control = extract("""
            if (id == 0) {
                return;
            } else {
                other();
            }
            marker();
            """);
        assertThat(control.earlyExits()).isEmpty();
    }

    @Test
    void ifWithoutUnconditionalExitIsNotAnEarlyExit() {
        ControlContext control = extract("""
            if (id == 0) {
                other();
            }
            marker();
            """);
        assertThat(control.earlyExits()).isEmpty();
    }

    @Test
    void nestedIfWithoutElseIsNotAnUnconditionalExit() {
        // then-branch is `{ if (other) return; }`: it exits only when the INNER condition also holds, so
        // the outer guard does not unconditionally bypass the log - unlike the flat `{ ...; return; }` case.
        ControlContext control = extract("""
            if (id == 0) {
                if (id == 1) {
                    return;
                }
            }
            marker();
            """);
        assertThat(control.earlyExits()).isEmpty();
    }

    @Test
    void nestedIfElseBothExitingCountsAsUnconditionalExit() {
        ControlContext control = extract("""
            if (id == 0) {
                if (id < 0) {
                    return;
                } else {
                    throw new IllegalStateException();
                }
            }
            marker();
            """);
        assertThat(control.earlyExits()).extracting(EarlyExit::exitKind).containsExactly("return");
    }

    @Test
    void outerLevelEarlyExitsComeBeforeInnerLevelOnes() {
        ControlContext control = extract("""
            if (id == 1) {
                return;
            }
            if (id > 0) {
                if (id == 2) {
                    return;
                }
                marker();
            }
            """);
        assertThat(control.earlyExits()).extracting(EarlyExit::text)
            .containsExactly("id == 1", "id == 2");
    }

    // --- preceding[] -------------------------------------------------------------------------------

    @Test
    void precedingIsNearestFirstAcrossBlocks() {
        ControlContext control = extract("""
            prepareOutside();
            if (id > 0) {
                prepareInside();
                marker();
            }
            """);
        assertThat(control.preceding()).extracting(PrecedingStatement::kind, PrecedingStatement::text)
            .containsExactly(
                tuple("call", "prepareInside();"),
                tuple("call", "prepareOutside();"));
    }

    @Test
    void precedingIsCappedAtMaxPrecedingStatements() {
        ControlContext control = extract("""
            a();
            b();
            c();
            marker();
            """, 2);
        assertThat(control.preceding()).extracting(PrecedingStatement::text)
            .containsExactly("c();", "b();");
    }

    @Test
    void precedingIsEmptyAtStartOfMethod() {
        ControlContext control = extract("marker();");
        assertThat(control.preceding()).isEmpty();
    }

    @Test
    void precedingClassifiesEveryKind() {
        ControlContext control = extract("""
            int total = 0;
            total = 1;
            compute();
            return;
            marker();
            """);
        // unreachable-after-return body is fine here: JavaParser only checks syntax, not reachability.
        assertThat(control.preceding()).extracting(PrecedingStatement::kind)
            .containsExactly("return", "call", "assign", "var_decl");
    }

    @Test
    void precedingIfAndLoopKinds() {
        ControlContext control = extract("""
            if (id > 0) {
                other();
            }
            for (int i = 0; i < id; i++) {
                other();
            }
            marker();
            """);
        assertThat(control.preceding()).extracting(PrecedingStatement::kind)
            .containsExactly("loop", "if");
    }

    @Test
    void precedingTextIsTruncatedAt200Characters() {
        String longCondition = "a".repeat(250);
        ControlContext control = extract("""
            if (%s.equals("x")) {
                other();
            }
            marker();
            """.formatted(longCondition));
        assertThat(control.preceding().get(0).text()).hasSize(200);
    }

    // --- calls_before[] ----------------------------------------------------------------------------

    @Test
    void callsBeforeUsesSimpleScopeTarget() {
        ControlContext control = extract("""
            ownerRepository.save(owner);
            marker();
            """);
        assertThat(control.callsBefore()).extracting(CallSite::text, CallSite::target)
            .containsExactly(tuple("ownerRepository.save(owner)", "ownerRepository.save"));
    }

    @Test
    void callsBeforeBareMethodHasNoScopePrefix() {
        ControlContext control = extract("""
            prepare();
            marker();
            """);
        assertThat(control.callsBefore()).extracting(CallSite::target).containsExactly("prepare");
    }

    @Test
    void callsBeforeFindsNestedCallsInSourceOrder() {
        ControlContext control = extract("""
            Owner o = ownerRepository.findById(id).orElseThrow();
            marker();
            """);
        assertThat(control.callsBefore()).extracting(CallSite::target)
            .containsExactly("ownerRepository.findById", "orElseThrow");
    }

    @Test
    void callsBeforeExcludesCallsInsideLambdaBodies() {
        ControlContext control = extract("""
            Owner o = ownerRepository.findById(id).orElseGet(() -> fallback());
            marker();
            """);
        assertThat(control.callsBefore()).extracting(CallSite::target)
            .containsExactly("ownerRepository.findById", "orElseGet");
    }

    @Test
    void callsBeforeExcludesCallsInsideAnonymousClassBodies() {
        ControlContext control = extract("""
            Runnable r = new Runnable() {
                public void run() {
                    hiddenCall();
                }
            };
            marker();
            """);
        assertThat(control.callsBefore()).isEmpty();
    }

    @Test
    void callsBeforeIncludesCallsInPlainConstructorArguments() {
        ControlContext control = extract("""
            Foo f = new Foo(prepare());
            marker();
            """);
        assertThat(control.callsBefore()).extracting(CallSite::target).containsExactly("prepare");
    }

    @Test
    void callsBeforeOnlyCoversStatementsThatMadeItIntoPreceding() {
        ControlContext control = extract("""
            farAway();
            near();
            marker();
            """, 1);
        assertThat(control.preceding()).extracting(PrecedingStatement::text).containsExactly("near();");
        assertThat(control.callsBefore()).extracting(CallSite::target).containsExactly("near");
    }

    @Test
    void callSitesAreNotYetResolvedByT11() {
        ControlContext control = extract("""
            prepare();
            marker();
            """);
        assertThat(control.callsBefore()).extracting(CallSite::targetMethodId, CallSite::resolved)
            .containsExactly(tuple(null, false));
    }
}
