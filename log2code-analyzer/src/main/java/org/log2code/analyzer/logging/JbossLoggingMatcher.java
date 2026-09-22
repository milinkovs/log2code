package org.log2code.analyzer.logging;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.log2code.core.model.Level;

/**
 * {@code org.jboss.logging.Logger}/{@code BasicLogger}: plain {@code trace..fatal(Object [, Throwable])}
 * (step 5.1, fixed position like JCL), plus the {@code *f} (String.format style) and {@code *v}
 * (MessageFormat style) variants, each with an optional <em>leading</em> {@code Throwable} - an
 * unambiguous position by itself, but overlapping in arity with the throwable-less form, so it is
 * resolved with the same expression-shape heuristic as step 5.2.
 */
final class JbossLoggingMatcher implements LogMethodMatcher {

    private static final Set<String> PLAIN_LEVELS = Set.of("trace", "debug", "info", "warn", "error", "fatal");
    private static final LogMethodMatcher PLAIN =
        new FixedPositionThrowableMatcher(FixedPositionThrowableMatcher.ThrowablePosition.LAST, PLAIN_LEVELS);

    @Override
    public Optional<MatchedCall> match(MethodCallExpr call) {
        Optional<MatchedCall> plain = PLAIN.match(call);
        if (plain.isPresent()) {
            return plain;
        }
        String levelName = formatStyleLevelName(call.getNameAsString());
        if (levelName == null) {
            return Optional.empty();
        }
        return matchFormatStyle(Level.parse(levelName), call.getArguments());
    }

    /** {@code tracef}/{@code tracev} -> {@code "trace"}; {@code null} if not an {@code *f}/{@code *v} method. */
    private static String formatStyleLevelName(String methodName) {
        if (methodName.length() < 2) {
            return null;
        }
        char suffix = methodName.charAt(methodName.length() - 1);
        if (suffix != 'f' && suffix != 'v') {
            return null;
        }
        String levelName = methodName.substring(0, methodName.length() - 1);
        return PLAIN_LEVELS.contains(levelName) ? levelName : null;
    }

    private Optional<MatchedCall> matchFormatStyle(Level level, NodeList<Expression> args) {
        if (args.isEmpty()) {
            return Optional.empty();
        }
        Expression first = args.get(0);
        if (args.size() >= 2 && ThrowableHeuristics.looksLikeThrowable(first)) {
            // (Throwable t, String format, Object... params)
            List<Expression> messageArgs = new ArrayList<>();
            messageArgs.add(args.get(1));
            messageArgs.addAll(args.subList(2, args.size()));
            return Optional.of(new MatchedCall(level, false, List.copyOf(messageArgs), first));
        }
        // (String format, Object... params)
        return Optional.of(new MatchedCall(level, false, List.copyOf(args), null));
    }
}
