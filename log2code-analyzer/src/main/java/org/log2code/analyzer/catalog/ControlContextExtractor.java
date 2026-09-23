package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EarlyExit;
import org.log2code.core.model.PrecedingStatement;

/**
 * Extracts the level-2 control context (T11) of a log statement: the enclosing constructs that must be
 * true to reach it ({@link ControlContext#conditions()}), sibling guard clauses that would bypass it
 * ({@link ControlContext#earlyExits()}), the closest statements executed before it
 * ({@link ControlContext#preceding()}), and the method calls found in those
 * ({@link ControlContext#callsBefore()}). One upward walk from the log call's terminal node to the
 * enclosing method-like unit (the same boundary as {@link MethodContextResolver}) computes all four:
 * at each level, sibling statements in the current statement-list container (a {@link BlockStmt} or a
 * {@link SwitchEntry}, both are "the same or an enclosing block") feed {@code preceding}/{@code
 * earlyExits}/{@code callsBefore}, and a match against a known construct type
 * ({@code if}/{@code else}/loop/{@code switch_case}/{@code try}/{@code catch}/{@code finally}/
 * {@code lambda}/{@code synchronized}) feeds {@code conditions}. See ADR-011 for the design decisions
 * this makes where the task text under-specifies (e.g. {@code text} for constructs without a boolean
 * condition, the {@code calls_before} target format).
 */
public final class ControlContextExtractor {

    static final int TEXT_LIMIT = 200;

    private ControlContextExtractor() {
    }

    public static ControlContext extract(Node logCallNode, MethodContext methodContext, int maxPrecedingStatements) {
        List<Condition> conditionsBottomUp = new ArrayList<>();
        List<EarlyExit> earlyExits = new ArrayList<>();
        List<PrecedingStatement> preceding = new ArrayList<>();
        List<CallSite> callsBefore = new ArrayList<>();

        Node current = logCallNode;
        while (true) {
            Node parent = current.getParentNode()
                .orElseThrow(() -> new IllegalStateException("no enclosing method for " + logCallNode));

            collectFromContainer(parent, current, maxPrecedingStatements, earlyExits, preceding, callsBefore);

            Condition match = matchCondition(parent, current);
            if (match != null) {
                conditionsBottomUp.add(match);
                current = parent;
                continue;
            }
            if (MethodContextResolver.isBoundary(parent, current)) {
                break;
            }
            current = parent;
        }

        List<Condition> conditions = new ArrayList<>(conditionsBottomUp);
        Collections.reverse(conditions);
        return new ControlContext(List.copyOf(conditions), List.copyOf(earlyExits),
            List.copyOf(preceding), List.copyOf(callsBefore));
    }

    // --- conditions[] --------------------------------------------------------------------------

    private static Condition matchCondition(Node parent, Node current) {
        if (parent instanceof IfStmt ifStmt) {
            if (current == ifStmt.getThenStmt()) {
                return new Condition("if", truncate(ifStmt.getCondition().toString()), AstLines.startLine(ifStmt), false);
            }
            if (ifStmt.getElseStmt().filter(e -> e == current).isPresent()) {
                Statement elseStmt = ifStmt.getElseStmt().orElseThrow();
                return new Condition("else", truncate(ifStmt.getCondition().toString()), AstLines.startLine(elseStmt), true);
            }
            return null;
        }
        if (parent instanceof ForStmt forStmt && current == forStmt.getBody()) {
            String text = forStmt.getCompare().map(Expression::toString).orElse("");
            return new Condition("loop", truncate(text), AstLines.startLine(forStmt), false);
        }
        if (parent instanceof ForEachStmt forEachStmt && current == forEachStmt.getBody()) {
            String text = forEachStmt.getVariable() + " : " + forEachStmt.getIterable();
            return new Condition("loop", truncate(text), AstLines.startLine(forEachStmt), false);
        }
        if (parent instanceof WhileStmt whileStmt && current == whileStmt.getBody()) {
            return new Condition("loop", truncate(whileStmt.getCondition().toString()), AstLines.startLine(whileStmt), false);
        }
        if (parent instanceof DoStmt doStmt && current == doStmt.getBody()) {
            return new Condition("loop", truncate(doStmt.getCondition().toString()), AstLines.startLine(doStmt), false);
        }
        if (parent instanceof SwitchEntry switchEntry) {
            return new Condition("switch_case", truncate(switchCaseText(switchEntry)), AstLines.startLine(switchEntry), false);
        }
        if (parent instanceof CatchClause catchClause) {
            String type = catchClause.getParameter().getType().asString();
            return new Condition("catch", truncate(type), AstLines.startLine(catchClause), false);
        }
        if (parent instanceof SynchronizedStmt syncStmt && current == syncStmt.getBody()) {
            return new Condition("synchronized", truncate(syncStmt.getExpression().toString()), AstLines.startLine(syncStmt), false);
        }
        if (parent instanceof TryStmt tryStmt) {
            if (current == tryStmt.getTryBlock()) {
                return new Condition("try", "", AstLines.startLine(tryStmt), false);
            }
            if (tryStmt.getFinallyBlock().filter(f -> f == current).isPresent()) {
                Statement finallyBlock = tryStmt.getFinallyBlock().orElseThrow();
                return new Condition("finally", "", AstLines.startLine(finallyBlock), false);
            }
            return null; // e.g. a try-with-resources resource expression; keep walking
        }
        if (parent instanceof LambdaExpr lambdaExpr) {
            String params = lambdaExpr.getParameters().stream()
                .map(p -> p.getNameAsString())
                .collect(Collectors.joining(", "));
            return new Condition("lambda", truncate(params), AstLines.startLine(lambdaExpr), false);
        }
        return null;
    }

    private static String switchCaseText(SwitchEntry entry) {
        String selector = entry.getParentNode().map(p -> {
            if (p instanceof SwitchStmt s) {
                return s.getSelector().toString();
            }
            if (p instanceof SwitchExpr s) {
                return s.getSelector().toString();
            }
            return "";
        }).orElse("");
        String label = entry.getLabels().isEmpty()
            ? "default"
            : entry.getLabels().stream().map(Node::toString).collect(Collectors.joining(", "));
        return selector + " == " + label;
    }

    // --- preceding[] / early_exits[] / calls_before[] -------------------------------------------

    private static List<Statement> statementsContainerOf(Node parent) {
        if (parent instanceof BlockStmt block) {
            return block.getStatements();
        }
        if (parent instanceof SwitchEntry entry) {
            return entry.getStatements();
        }
        return null;
    }

    private static void collectFromContainer(Node parent, Node current, int maxPrecedingStatements,
            List<EarlyExit> earlyExits, List<PrecedingStatement> preceding, List<CallSite> callsBefore) {
        List<Statement> container = statementsContainerOf(parent);
        if (container == null || !(current instanceof Statement currentStatement)) {
            return;
        }
        int index = container.indexOf(currentStatement);
        if (index <= 0) {
            return;
        }
        List<Statement> before = container.subList(0, index);

        List<EarlyExit> levelExits = new ArrayList<>();
        for (Statement s : before) {
            earlyExit(s).ifPresent(levelExits::add);
        }
        earlyExits.addAll(0, levelExits); // outer levels are visited later; keep them ahead of already-collected inner ones

        for (int i = before.size() - 1; i >= 0 && preceding.size() < maxPrecedingStatements; i--) {
            Statement s = before.get(i);
            preceding.add(classify(s));
            callsBefore.addAll(CallSiteFinder.find(s));
        }
    }

    private static PrecedingStatement classify(Statement statement) {
        return new PrecedingStatement(classifyKind(statement), truncate(statement.toString()), AstLines.startLine(statement));
    }

    private static String classifyKind(Statement statement) {
        if (statement instanceof ExpressionStmt es) {
            Expression expr = es.getExpression();
            if (expr instanceof VariableDeclarationExpr) {
                return "var_decl";
            }
            if (expr instanceof AssignExpr) {
                return "assign";
            }
            if (expr instanceof MethodCallExpr) {
                return "call";
            }
            return "other";
        }
        if (statement instanceof ReturnStmt) {
            return "return";
        }
        if (statement instanceof ThrowStmt) {
            return "throw";
        }
        if (statement instanceof IfStmt) {
            return "if";
        }
        if (statement instanceof ForStmt || statement instanceof ForEachStmt
            || statement instanceof WhileStmt || statement instanceof DoStmt) {
            return "loop";
        }
        return "other";
    }

    /** A sibling {@code if} with no {@code else}, whose then-branch unconditionally exits (0.9 T11). */
    private static Optional<EarlyExit> earlyExit(Statement statement) {
        if (!(statement instanceof IfStmt ifStmt) || ifStmt.getElseStmt().isPresent()) {
            return Optional.empty();
        }
        return exitKindOf(ifStmt.getThenStmt())
            .map(exitKind -> new EarlyExit(truncate(ifStmt.getCondition().toString()), AstLines.startLine(ifStmt), exitKind));
    }

    private static Optional<String> exitKindOf(Statement statement) {
        if (statement instanceof BlockStmt block) {
            List<Statement> statements = block.getStatements();
            if (statements.isEmpty()) {
                return Optional.empty();
            }
            return exitKindOf(statements.get(statements.size() - 1));
        }
        if (statement instanceof ReturnStmt) {
            return Optional.of("return");
        }
        if (statement instanceof ThrowStmt) {
            return Optional.of("throw");
        }
        if (statement instanceof ContinueStmt) {
            return Optional.of("continue");
        }
        if (statement instanceof BreakStmt) {
            return Optional.of("break");
        }
        if (statement instanceof IfStmt nested && nested.getElseStmt().isPresent()) {
            Optional<String> thenExit = exitKindOf(nested.getThenStmt());
            Optional<String> elseExit = exitKindOf(nested.getElseStmt().orElseThrow());
            return thenExit.isPresent() && elseExit.isPresent() ? thenExit : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Collapses whitespace (a multi-statement {@code if}/block spans several lines in JavaParser's
     * pretty-printed {@code toString()}, using the JVM's platform line separator) into a single-line
     * preview, then truncates to {@link #TEXT_LIMIT} characters (0.9's "izvorni tekst, skraćen na 200
     * znakova"). Without this, the same source would serialize differently on Windows vs. Linux.
     */
    static String truncate(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= TEXT_LIMIT ? normalized : normalized.substring(0, TEXT_LIMIT);
    }
}
