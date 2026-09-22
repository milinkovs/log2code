package org.log2code.analyzer.logging;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.log2code.core.model.Level;

/**
 * {@code level(String, Object...)} style methods (SLF4J, and the equivalent Log4j2 methods): the
 * method name is the level, the first argument is the message, and any extra trailing argument may be
 * a throwable (step 5.2, {@link ThrowableHeuristics#classify}).
 */
record VarargsHeuristicMatcher(Set<String> levelMethodNames) implements LogMethodMatcher {

    @Override
    public Optional<MatchedCall> match(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!levelMethodNames.contains(name)) {
            return Optional.empty();
        }
        NodeList<Expression> args = call.getArguments();
        if (args.isEmpty()) {
            return Optional.empty();
        }
        List<Expression> extras = args.subList(1, args.size());
        return Optional.of(ThrowableHeuristics.classify(Level.parse(name), false, args.get(0), extras));
    }
}
