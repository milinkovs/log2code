package org.log2code.analyzer.logging;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.log2code.core.model.Level;

/**
 * Finds every log statement in Java source (T08): builds the code unit's {@link TypeIndex} (step 3,
 * pass 1) once, then walks each compilation unit's {@link MethodCallExpr} nodes, resolving each one's
 * scope to a known logger (typed/inherited, step 3 pass 2; heuristic fallback, step 3.3) and
 * classifying the call itself (level/message/throwable, steps 4-5). The SLF4J fluent API
 * ({@code atInfo()...log(...)}) is handled separately, since it spans a chain of calls rather than one.
 */
public final class LogCallDetector {

    /** Step 3.3's heuristic receiver-name pattern. */
    private static final Pattern HEURISTIC_NAME_PATTERN =
        Pattern.compile("^(log|logger|LOG|LOGGER|LOGGER_[A-Z_]+|logger[A-Z]\\w*)$");

    private LogCallDetector() {
    }

    /**
     * Detects log calls in every unit of one code unit (project, or one dependency's sources), sharing
     * a single {@link TypeIndex} across them so inheritance can be resolved across files. The result is
     * in the same order as {@code units}, one list per unit.
     */
    public static List<List<LogCall>> detectAll(List<CompilationUnit> units) {
        TypeIndex typeIndex = CodeUnitIndexer.index(units);
        List<List<LogCall>> result = new ArrayList<>(units.size());
        for (CompilationUnit unit : units) {
            result.add(detect(unit, typeIndex));
        }
        return result;
    }

    /** Convenience for a single, self-contained file (no cross-file inheritance to resolve). */
    public static List<LogCall> detectInSingleUnit(CompilationUnit unit) {
        return detect(unit, CodeUnitIndexer.index(List.of(unit)));
    }

    static List<LogCall> detect(CompilationUnit unit, TypeIndex typeIndex) {
        TypeNameResolver resolver = new TypeNameResolver(unit);
        List<LogCall> results = new ArrayList<>();
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            Optional<Expression> scope = call.getScope();
            if (scope.isEmpty()) {
                continue;
            }
            tryPlain(call, scope.get(), typeIndex, resolver)
                .or(() -> tryFluentSlf4j(call, typeIndex, resolver))
                .ifPresent(results::add);
        }
        return results;
    }

    private static Optional<LogCall> tryPlain(MethodCallExpr call, Expression scope, TypeIndex typeIndex, TypeNameResolver resolver) {
        Optional<LoggerFieldResolver.Resolution> resolution = LoggerFieldResolver.resolve(scope, call, typeIndex, resolver);

        String api;
        String loggerName;
        String loggerNameKind;
        String detection;
        if (resolution.isPresent()) {
            api = resolution.get().api();
            loggerName = resolution.get().loggerName();
            loggerNameKind = resolution.get().loggerNameKind();
            detection = resolution.get().detection();
        } else {
            Optional<String> candidateName = LoggerFieldResolver.candidateNameForHeuristic(scope);
            if (candidateName.isEmpty()
                || !HEURISTIC_NAME_PATTERN.matcher(candidateName.get()).matches()
                || !LoggingApiRegistry.isKnownLogMethodName(call.getNameAsString())) {
                return Optional.empty();
            }
            api = LoggingApi.UNKNOWN;
            loggerName = null;
            loggerNameKind = LoggerNameKind.UNKNOWN;
            detection = Detection.HEURISTIC;
        }

        Optional<MatchedCall> matched = Detection.HEURISTIC.equals(detection)
            ? matchHeuristically(call)
            : LoggingApiRegistry.matcherForApi(api).flatMap(matcher -> matcher.match(call));
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        MatchedCall m = matched.get();
        return Optional.of(new LogCall(call, api, scope.toString(), loggerName, loggerNameKind,
            m.level(), m.levelDynamic(), m.messageArgs(), m.throwableArg(), detection, inLambda(call)));
    }

    /**
     * The receiver's own method name is a recognized log method, but its type could not be resolved
     * (step 3.3): apply the same message/throwable shape as the {@code level(String, Object...)} APIs,
     * with {@link Level#UNKNOWN} for a method name that is not itself one of the plain level names
     * (e.g. {@code log}, {@code logp}, an {@code *f}/{@code *v} JBoss-style method).
     */
    private static Optional<MatchedCall> matchHeuristically(MethodCallExpr call) {
        NodeList<Expression> args = call.getArguments();
        if (args.isEmpty()) {
            return Optional.empty();
        }
        Level level = Level.parse(call.getNameAsString());
        List<Expression> extras = args.subList(1, args.size());
        return Optional.of(ThrowableHeuristics.classify(level, false, args.get(0), extras));
    }

    /**
     * SLF4J fluent API: {@code log.atInfo().log("msg {}", arg)} or
     * {@code log.atError().setMessage("msg").setCause(t).log()}. Only reached when {@code terminalCall}
     * (named {@code log}) did not resolve directly - i.e. its scope is itself a chain of builder calls,
     * not a logger expression.
     */
    private static Optional<LogCall> tryFluentSlf4j(MethodCallExpr terminalCall, TypeIndex typeIndex, TypeNameResolver resolver) {
        if (!"log".equals(terminalCall.getNameAsString())) {
            return Optional.empty();
        }
        Optional<Expression> scope = terminalCall.getScope();
        if (scope.isEmpty() || !(scope.get() instanceof MethodCallExpr)) {
            return Optional.empty();
        }

        List<MethodCallExpr> chain = new ArrayList<>();
        Expression cursor = scope.get();
        while (cursor instanceof MethodCallExpr step) {
            chain.add(0, step);
            cursor = step.getScope().orElse(null);
            if (cursor == null) {
                return Optional.empty(); // not rooted at an actual expression - not a fluent-on-logger chain
            }
        }

        MethodCallExpr rootStep = chain.get(0);
        Level level = switch (rootStep.getNameAsString()) {
            case "atTrace" -> Level.TRACE;
            case "atDebug" -> Level.DEBUG;
            case "atInfo" -> Level.INFO;
            case "atWarn" -> Level.WARN;
            case "atError" -> Level.ERROR;
            default -> null;
        };
        if (level == null) {
            return Optional.empty();
        }

        Optional<LoggerFieldResolver.Resolution> resolution = LoggerFieldResolver.resolve(cursor, terminalCall, typeIndex, resolver);
        if (resolution.isEmpty() || !LoggingApi.SLF4J.equals(resolution.get().api())) {
            return Optional.empty();
        }

        Expression message = null;
        List<Expression> extraArgs = new ArrayList<>();
        Expression throwableArg = null;
        for (int i = 1; i < chain.size(); i++) {
            MethodCallExpr step = chain.get(i);
            NodeList<Expression> stepArgs = step.getArguments();
            switch (step.getNameAsString()) {
                case "setMessage" -> {
                    if (!stepArgs.isEmpty()) {
                        message = stepArgs.get(0);
                    }
                }
                case "addArgument" -> {
                    if (!stepArgs.isEmpty()) {
                        extraArgs.add(stepArgs.get(0));
                    }
                }
                case "setCause" -> {
                    if (!stepArgs.isEmpty()) {
                        throwableArg = stepArgs.get(0);
                    }
                }
                default -> {
                    // an unrecognized builder step (e.g. addKeyValue, addMarker): ignore it and keep
                    // walking the chain rather than abandoning fluent detection altogether.
                }
            }
        }
        NodeList<Expression> terminalArgs = terminalCall.getArguments();
        if (message == null) {
            if (terminalArgs.isEmpty()) {
                return Optional.empty(); // no message anywhere in the chain: not a complete statement
            }
            message = terminalArgs.get(0);
            extraArgs.addAll(terminalArgs.subList(1, terminalArgs.size()));
        } else {
            extraArgs.addAll(terminalArgs);
        }

        List<Expression> messageArgs = new ArrayList<>();
        messageArgs.add(message);
        messageArgs.addAll(extraArgs);

        return Optional.of(new LogCall(terminalCall, LoggingApi.SLF4J, cursor.toString(),
            resolution.get().loggerName(), resolution.get().loggerNameKind(),
            level, false, List.copyOf(messageArgs), throwableArg,
            resolution.get().detection(), inLambda(terminalCall)));
    }

    private static boolean inLambda(Node node) {
        Node current = node;
        while (true) {
            Optional<Node> parent = current.getParentNode();
            if (parent.isEmpty()) {
                return false;
            }
            Node p = parent.get();
            if (p instanceof LambdaExpr) {
                return true;
            }
            if (p instanceof CallableDeclaration<?> || p instanceof InitializerDeclaration) {
                return false;
            }
            current = p;
        }
    }
}
