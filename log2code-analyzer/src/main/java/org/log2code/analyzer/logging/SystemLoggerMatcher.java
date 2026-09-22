package org.log2code.analyzer.logging;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Optional;

/** {@code java.lang.System.Logger}: {@code log(Level, String|Supplier [, Object... | Throwable])}. */
final class SystemLoggerMatcher implements LogMethodMatcher {

    @Override
    public Optional<MatchedCall> match(MethodCallExpr call) {
        if (!"log".equals(call.getNameAsString())) {
            return Optional.empty();
        }
        NodeList<Expression> args = call.getArguments();
        if (args.isEmpty()) {
            return Optional.empty();
        }
        LevelArgs.Resolved levelResult = LevelArgs.resolve(args.get(0));
        List<Expression> rest = args.subList(1, args.size());
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        List<Expression> extras = rest.subList(1, rest.size());
        return Optional.of(ThrowableHeuristics.classify(levelResult.level(), levelResult.dynamic(), rest.get(0), extras));
    }
}
