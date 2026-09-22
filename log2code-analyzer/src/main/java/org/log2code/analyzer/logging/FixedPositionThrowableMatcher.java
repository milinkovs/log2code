package org.log2code.analyzer.logging;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.log2code.core.model.Level;

/**
 * {@code level(Object)} / {@code level(Object, Throwable)} (JCL, Tomcat juli) or
 * {@code level(Throwable, CharSequence)} (Spring {@code LogAccessor}) style methods: an unambiguous,
 * fixed 2-argument signature, so the throwable position is known from the API alone (step 5.1) - no
 * heuristic needed.
 */
record FixedPositionThrowableMatcher(ThrowablePosition throwablePosition, Set<String> levelMethodNames)
    implements LogMethodMatcher {

    enum ThrowablePosition {
        FIRST,
        LAST
    }

    @Override
    public Optional<MatchedCall> match(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!levelMethodNames.contains(name)) {
            return Optional.empty();
        }
        NodeList<Expression> args = call.getArguments();
        Level level = Level.parse(name);
        if (args.size() == 1) {
            return Optional.of(new MatchedCall(level, false, List.of(args.get(0)), null));
        }
        if (args.size() == 2) {
            boolean throwableFirst = throwablePosition == ThrowablePosition.FIRST;
            Expression throwableArg = throwableFirst ? args.get(0) : args.get(1);
            Expression message = throwableFirst ? args.get(1) : args.get(0);
            return Optional.of(new MatchedCall(level, false, List.of(message), throwableArg));
        }
        return Optional.empty();
    }
}
