package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.PrecedingStatement;

/**
 * Finds the method calls executed as part of one {@link PrecedingStatement} (T11's {@code
 * control.calls_before}). Descends into everything evaluated immediately when the statement runs, but
 * not into a lambda body or an anonymous class body: those calls are deferred, not executed as a step
 * of reaching the log statement (ADR-011). {@link CallSite#targetMethodId()} and {@link
 * CallSite#resolved()} are left unset here; T13 fills them in once the call graph exists.
 */
final class CallSiteFinder {

    private CallSiteFinder() {
    }

    static List<CallSite> find(Statement statement) {
        List<MethodCallExpr> calls = new ArrayList<>();
        collect(statement, calls);
        // Sort by the method NAME token's position, not the call expression's own range: a chained call
        // like "a.b().c()" has both the outer (c) and inner (b) MethodCallExpr begin at the same point
        // ("a"), so ordering by the call's own range would not reflect reading order.
        calls.sort(Comparator.comparingInt((MethodCallExpr c) -> AstLines.startLine(c.getName()))
            .thenComparingInt(c -> AstLines.startColumn(c.getName())));

        List<CallSite> sites = new ArrayList<>(calls.size());
        for (MethodCallExpr call : calls) {
            sites.add(new CallSite(AstLines.startLine(call), ControlContextExtractor.truncate(call.toString()), target(call), null, false));
        }
        return sites;
    }

    private static void collect(Node node, List<MethodCallExpr> out) {
        if (node instanceof LambdaExpr) {
            return;
        }
        if (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
            for (Expression argument : creation.getArguments()) {
                collect(argument, out);
            }
            return;
        }
        if (node instanceof MethodCallExpr call) {
            out.add(call);
        }
        for (Node child : node.getChildNodes()) {
            collect(child, out);
        }
    }

    /** {@code scopeText.methodName} when the scope is a simple name/field/{@code this}, else just {@code methodName}. */
    private static String target(MethodCallExpr call) {
        return call.getScope()
            .filter(CallSiteFinder::isSimpleScope)
            .map(scope -> scope + "." + call.getNameAsString())
            .orElseGet(call::getNameAsString);
    }

    private static boolean isSimpleScope(Expression scope) {
        return scope instanceof NameExpr || scope instanceof FieldAccessExpr || scope instanceof ThisExpr;
    }
}
