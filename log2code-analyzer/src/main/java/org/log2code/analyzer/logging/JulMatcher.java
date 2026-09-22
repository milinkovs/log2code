package org.log2code.analyzer.logging;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.log2code.core.model.Level;

/**
 * {@code java.util.logging.Logger}: the single-argument convenience methods ({@code severe}...
 * {@code finest}), and {@code log}/{@code logp}, whose overloads differ only in the static type of a
 * trailing argument ({@code Object}, {@code Object[]} or {@code Throwable}) - resolved the same way as
 * the SLF4J/Log4j2 heuristic (step 5.2), since the task does not single out JUL as a fixed-position API.
 */
final class JulMatcher implements LogMethodMatcher {

    private static final Set<String> CONVENIENCE_METHODS =
        Set.of("severe", "warning", "info", "config", "fine", "finer", "finest");

    @Override
    public Optional<MatchedCall> match(MethodCallExpr call) {
        String name = call.getNameAsString();
        NodeList<Expression> args = call.getArguments();
        if (CONVENIENCE_METHODS.contains(name)) {
            // severe/warning/.../finest(String | Supplier): message only, never a throwable.
            if (args.size() != 1) {
                return Optional.empty();
            }
            return Optional.of(new MatchedCall(Level.parse(name), false, List.of(args.get(0)), null));
        }
        if ("log".equals(name)) {
            return matchLog(args);
        }
        if ("logp".equals(name)) {
            return matchLogp(args);
        }
        return Optional.empty();
    }

    private Optional<MatchedCall> matchLog(NodeList<Expression> args) {
        if (args.isEmpty()) {
            return Optional.empty();
        }
        LevelArgs.Resolved levelResult = LevelArgs.resolve(args.get(0));
        List<Expression> rest = args.subList(1, args.size());
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        if (rest.size() == 1) {
            // log(Level, String | Supplier)
            return Optional.of(new MatchedCall(levelResult.level(), levelResult.dynamic(), List.of(rest.get(0)), null));
        }
        if (rest.size() == 2) {
            // log(Level, String, Object|Object[]|Throwable) or log(Level, Throwable, Supplier<String>)
            Expression first = rest.get(0);
            Expression second = rest.get(1);
            if (ThrowableHeuristics.looksLikeThrowable(first)) {
                return Optional.of(new MatchedCall(levelResult.level(), levelResult.dynamic(), List.of(second), first));
            }
            if (ThrowableHeuristics.looksLikeThrowable(second)) {
                return Optional.of(new MatchedCall(levelResult.level(), levelResult.dynamic(), List.of(first), second));
            }
            return Optional.of(new MatchedCall(levelResult.level(), levelResult.dynamic(), List.of(first, second), null));
        }
        return Optional.empty();
    }

    private Optional<MatchedCall> matchLogp(NodeList<Expression> args) {
        // logp(Level, String sourceClass, String sourceMethod, String msg [, Object|Object[]|Throwable|Supplier])
        if (args.size() < 4) {
            return Optional.empty();
        }
        LevelArgs.Resolved levelResult = LevelArgs.resolve(args.get(0));
        Expression message = args.get(3);
        if (args.size() == 4) {
            return Optional.of(new MatchedCall(levelResult.level(), levelResult.dynamic(), List.of(message), null));
        }
        if (args.size() == 5) {
            Expression extra = args.get(4);
            if (ThrowableHeuristics.looksLikeThrowable(extra)) {
                return Optional.of(new MatchedCall(levelResult.level(), levelResult.dynamic(), List.of(message), extra));
            }
            return Optional.of(new MatchedCall(levelResult.level(), levelResult.dynamic(), List.of(message, extra), null));
        }
        return Optional.empty();
    }
}
