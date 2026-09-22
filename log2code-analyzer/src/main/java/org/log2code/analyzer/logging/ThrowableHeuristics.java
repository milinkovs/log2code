package org.log2code.analyzer.logging;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.log2code.core.model.Level;
import org.log2code.core.template.MessageTemplate;

/**
 * Throwable-argument heuristics shared by logging APIs whose signature does not fix the throwable's
 * position (T08 step 5.2: SLF4J, Log4j2, and - by the same reasoning - the other APIs whose message
 * method takes a trailing {@code Object...}).
 */
final class ThrowableHeuristics {

    /** Names commonly used for caught exceptions (step 5.2), matched case-sensitively as listed in the task. */
    private static final Set<String> EXCEPTION_LIKE_NAMES =
        Set.of("e", "ex", "exc", "t", "th", "err", "throwable", "cause", "exception");

    private ThrowableHeuristics() {
    }

    /**
     * Number of {@code {}} holes in {@code expr} if it is a plain string literal, otherwise {@code 0}
     * (best effort: a non-literal message is conservatively treated as having no holes, so an extra
     * trailing argument can still be recognized as a throwable by {@link #looksLikeThrowable}).
     */
    static int holeCountIfLiteral(Expression expr) {
        if (expr instanceof StringLiteralExpr literal) {
            return MessageTemplate.parse(literal.asString()).holeCount();
        }
        return 0;
    }

    /**
     * Whether {@code candidate} (typically the last call argument) looks like a throwable expression:
     * a {@code new *Exception(...)}/{@code new *Error(...)} expression, one of the common exception
     * variable names, or the parameter of an enclosing {@code catch} clause (step 5.2).
     */
    static boolean looksLikeThrowable(Expression candidate) {
        if (candidate instanceof ObjectCreationExpr creation) {
            String simpleName = creation.getType().getNameAsString();
            return simpleName.endsWith("Exception") || simpleName.endsWith("Error");
        }
        if (candidate instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            return EXCEPTION_LIKE_NAMES.contains(name) || isCatchParameterInScope(name, candidate);
        }
        return false;
    }

    /**
     * Applies the step-5.2 rule uniformly: {@code message} plus {@code extras} are all message
     * arguments unless there are more extras than {@code {}} holes in a literal message <em>and</em>
     * the last extra argument looks like a throwable, in which case it is split off.
     */
    static MatchedCall classify(Level level, boolean levelDynamic, Expression message, List<Expression> extras) {
        if (extras.isEmpty()) {
            return new MatchedCall(level, levelDynamic, List.of(message), null);
        }
        int holes = holeCountIfLiteral(message);
        Expression last = extras.get(extras.size() - 1);
        if (extras.size() > holes && looksLikeThrowable(last)) {
            List<Expression> messageArgs = new ArrayList<>();
            messageArgs.add(message);
            messageArgs.addAll(extras.subList(0, extras.size() - 1));
            return new MatchedCall(level, levelDynamic, List.copyOf(messageArgs), last);
        }
        List<Expression> messageArgs = new ArrayList<>();
        messageArgs.add(message);
        messageArgs.addAll(extras);
        return new MatchedCall(level, levelDynamic, List.copyOf(messageArgs), null);
    }

    /**
     * Walks up from {@code from} looking for an enclosing {@code catch} clause whose parameter is
     * {@code name}, stopping at the nearest enclosing method/constructor/initializer (a lambda or
     * anonymous class body does not stop the walk, since it may legitimately capture the catch
     * parameter).
     */
    private static boolean isCatchParameterInScope(String name, Node from) {
        for (Node current = from; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof CatchClause catchClause
                && catchClause.getParameter().getName().asString().equals(name)) {
                return true;
            }
            if (current instanceof CallableDeclaration<?> || current instanceof InitializerDeclaration) {
                return false;
            }
        }
        return false;
    }
}
