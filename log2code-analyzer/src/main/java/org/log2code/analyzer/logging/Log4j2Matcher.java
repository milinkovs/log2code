package org.log2code.analyzer.logging;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code org.apache.logging.log4j.Logger}: {@code trace..fatal(String, Object...)} /
 * {@code (String, Throwable)} (step 5.2 heuristic, same as SLF4J), plus the general
 * {@code log(Level, ...)} form.
 */
final class Log4j2Matcher implements LogMethodMatcher {

    private static final Set<String> LEVEL_METHODS = Set.of("trace", "debug", "info", "warn", "error", "fatal");
    private static final LogMethodMatcher PLAIN = new VarargsHeuristicMatcher(LEVEL_METHODS);

    @Override
    public Optional<MatchedCall> match(MethodCallExpr call) {
        Optional<MatchedCall> plain = PLAIN.match(call);
        if (plain.isPresent()) {
            return plain;
        }
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
