package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.log2code.core.model.EnclosingBlock;

/**
 * Resolves the level-1 enclosing block of a log statement (0.7/T10 step 1): the single nearest
 * construct directly wrapping it, one of {@code if}/{@code for}/{@code foreach}/{@code while}/
 * {@code do}/{@code switch_case}/{@code try}/{@code catch}/{@code finally}/{@code synchronized}/
 * {@code lambda}, or {@code method} if nothing else wraps it before the enclosing method-like unit
 * (see {@link MethodContextResolver}). Only the two kinds the task text calls out with extra content
 * ({@code if}: condition text and {@code then}/{@code else} branch; {@code catch}: exception type)
 * populate {@code condition}/{@code branch}; every other kind leaves them {@code null} (ADR-010).
 */
public final class EnclosingBlockResolver {

    private EnclosingBlockResolver() {
    }

    public static EnclosingBlock resolve(Node from, MethodContext methodContext) {
        Node current = from;
        while (true) {
            Node parent = current.getParentNode()
                .orElseThrow(() -> new IllegalStateException("no enclosing block/method for " + from));
            EnclosingBlock match = match(parent, current);
            if (match != null) {
                return match;
            }
            if (MethodContextResolver.isBoundary(parent, current)) {
                return new EnclosingBlock("method", null, null, methodContext.methodStartLine(), methodContext.methodEndLine());
            }
            current = parent;
        }
    }

    private static EnclosingBlock match(Node parent, Node current) {
        if (parent instanceof IfStmt ifStmt) {
            if (current == ifStmt.getThenStmt()) {
                return block("if", ifStmt.getCondition().toString(), "then", current);
            }
            if (ifStmt.getElseStmt().filter(e -> e == current).isPresent()) {
                return block("if", ifStmt.getCondition().toString(), "else", current);
            }
            return null; // inside the condition expression itself; not reachable for a real statement
        }
        if (parent instanceof ForStmt forStmt && current == forStmt.getBody()) {
            return block("for", null, null, current);
        }
        if (parent instanceof ForEachStmt forEachStmt && current == forEachStmt.getBody()) {
            return block("foreach", null, null, current);
        }
        if (parent instanceof WhileStmt whileStmt && current == whileStmt.getBody()) {
            return block("while", null, null, current);
        }
        if (parent instanceof DoStmt doStmt && current == doStmt.getBody()) {
            return block("do", null, null, current);
        }
        if (parent instanceof SwitchEntry) {
            return block("switch_case", null, null, parent);
        }
        if (parent instanceof CatchClause catchClause) {
            return block("catch", catchClause.getParameter().getType().asString(), null, catchClause.getBody());
        }
        if (parent instanceof SynchronizedStmt syncStmt && current == syncStmt.getBody()) {
            return block("synchronized", null, null, current);
        }
        if (parent instanceof TryStmt tryStmt) {
            if (current == tryStmt.getTryBlock()) {
                return block("try", null, null, current);
            }
            if (tryStmt.getFinallyBlock().filter(f -> f == current).isPresent()) {
                return block("finally", null, null, current);
            }
            return null; // e.g. a try-with-resources resource expression; keep walking
        }
        if (parent instanceof LambdaExpr) {
            return block("lambda", null, null, parent);
        }
        return null;
    }

    private static EnclosingBlock block(String kind, String condition, String branch, Node rangeNode) {
        return new EnclosingBlock(kind, condition, branch, AstLines.startLine(rangeNode), AstLines.endLine(rangeNode));
    }
}
