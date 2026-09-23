package org.log2code.analyzer.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.log2code.analyzer.catalog.AstLines;

/**
 * Finds every {@link MethodCallExpr}/{@link ObjectCreationExpr} in a method or constructor body (T13
 * step 2: "calls[] sadrži svaki MethodCallExpr i ObjectCreationExpr iz tela"). Unlike {@code
 * CallSiteFinder} (T11's narrower "steps executed on the way to this log statement"), this descends
 * into lambda bodies too: a method's own call graph does care what its lambdas eventually call, since
 * there is no separate {@code MethodInfo} for a lambda. It still does not descend into a nested type's
 * own members (a local or anonymous class's methods get their own, separate {@code MethodInfo} - found
 * independently via {@code findAll(MethodDeclaration.class)} over the whole file); an anonymous class's
 * constructor arguments are still visited, since those run eagerly as part of the enclosing call.
 */
final class GraphCallCollector {

    private GraphCallCollector() {
    }

    static List<Node> collect(Node body) {
        List<Node> calls = new ArrayList<>();
        walk(body, calls);
        calls.sort(Comparator.comparingInt(AstLines::startLine).thenComparingInt(AstLines::startColumn));
        return calls;
    }

    private static void walk(Node node, List<Node> out) {
        if (node instanceof TypeDeclaration<?>) {
            return; // local/anonymous class: its methods are enumerated, and walked, separately
        }
        if (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
            out.add(creation);
            for (Expression argument : creation.getArguments()) {
                walk(argument, out);
            }
            return; // arguments run eagerly; the anonymous body's own members do not belong to this call
        }
        if (node instanceof MethodCallExpr || node instanceof ObjectCreationExpr) {
            out.add(node);
        }
        for (Node child : node.getChildNodes()) {
            walk(child, out);
        }
    }
}
