package org.log2code.analyzer.logging;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The known logging API types (T08 step 1): fully-qualified type name -> {@link LoggerTypeSpec}. This
 * is the single source of truth for both {@link #forType(String)} (typed/inherited resolution) and
 * {@link #isKnownLogMethodName(String)} (the heuristic fallback, step 3.3).
 */
final class LoggingApiRegistry {

    private static final Map<String, LoggerTypeSpec> BY_FQN = buildRegistry();
    private static final Map<String, LogMethodMatcher> BY_API = buildApiIndex(BY_FQN);
    private static final Set<String> ALL_METHOD_NAMES = buildMethodNameIndex();

    private LoggingApiRegistry() {
    }

    /** The FQNs this registry recognizes (used by the code-unit type indexer to spot logger fields). */
    static Set<String> knownFqns() {
        return BY_FQN.keySet();
    }

    static Optional<LoggerTypeSpec> forType(String fqn) {
        return Optional.ofNullable(BY_FQN.get(fqn));
    }

    /**
     * The matcher for an already-known {@link LoggingApi} constant - used once a logger has been
     * resolved by name/initializer alone (a {@code var}-typed local variable, step 2), where no FQN
     * was available to go through {@link #forType(String)}.
     */
    static Optional<LogMethodMatcher> matcherForApi(String api) {
        return Optional.ofNullable(BY_API.get(api));
    }

    /**
     * Whether {@code methodName} is a recognized log method on <em>any</em> known API - used only by
     * the heuristic fallback (step 3.3) once typed/inherited resolution has already failed, so a
     * receiver whose name matches the logger-field pattern is not mistaken for a log call unless it is
     * also calling a method that looks like one (excludes {@code isDebugEnabled()}, {@code getName()},
     * {@code isLoggable}, step 6).
     */
    static boolean isKnownLogMethodName(String methodName) {
        return ALL_METHOD_NAMES.contains(methodName);
    }

    private static Map<String, LoggerTypeSpec> buildRegistry() {
        Map<String, LoggerTypeSpec> registry = new LinkedHashMap<>();

        Set<String> slf4jLevels = Set.of("trace", "debug", "info", "warn", "error");
        registry.put("org.slf4j.Logger",
            new LoggerTypeSpec(LoggingApi.SLF4J, new VarargsHeuristicMatcher(slf4jLevels)));

        Set<String> jclLevels = Set.of("trace", "debug", "info", "warn", "error", "fatal");
        LogMethodMatcher fixedLast =
            new FixedPositionThrowableMatcher(FixedPositionThrowableMatcher.ThrowablePosition.LAST, jclLevels);
        registry.put("org.apache.commons.logging.Log", new LoggerTypeSpec(LoggingApi.JCL, fixedLast));
        registry.put("org.apache.juli.logging.Log", new LoggerTypeSpec(LoggingApi.TOMCAT_JULI, fixedLast));

        LogMethodMatcher fixedFirst =
            new FixedPositionThrowableMatcher(FixedPositionThrowableMatcher.ThrowablePosition.FIRST, jclLevels);
        registry.put("org.springframework.core.log.LogAccessor",
            new LoggerTypeSpec(LoggingApi.SPRING_LOG_ACCESSOR, fixedFirst));

        registry.put("java.util.logging.Logger", new LoggerTypeSpec(LoggingApi.JUL, new JulMatcher()));
        registry.put("org.apache.logging.log4j.Logger", new LoggerTypeSpec(LoggingApi.LOG4J2, new Log4j2Matcher()));
        registry.put("java.lang.System.Logger",
            new LoggerTypeSpec(LoggingApi.SYSTEM_LOGGER, new SystemLoggerMatcher()));

        LogMethodMatcher jboss = new JbossLoggingMatcher();
        registry.put("org.jboss.logging.Logger", new LoggerTypeSpec(LoggingApi.JBOSS_LOGGING, jboss));
        registry.put("org.jboss.logging.BasicLogger", new LoggerTypeSpec(LoggingApi.JBOSS_LOGGING, jboss));

        return Map.copyOf(registry);
    }

    private static Map<String, LogMethodMatcher> buildApiIndex(Map<String, LoggerTypeSpec> byFqn) {
        Map<String, LogMethodMatcher> byApi = new LinkedHashMap<>();
        for (LoggerTypeSpec spec : byFqn.values()) {
            byApi.put(spec.api(), spec.matcher());
        }
        return Map.copyOf(byApi);
    }

    private static Set<String> buildMethodNameIndex() {
        Set<String> names = new LinkedHashSet<>(Set.of(
            "trace", "debug", "info", "warn", "error", "fatal", "log", "logp",
            "severe", "warning", "config", "fine", "finer", "finest"));
        for (String levelName : Set.of("trace", "debug", "info", "warn", "error", "fatal")) {
            names.add(levelName + "f");
            names.add(levelName + "v");
        }
        return Set.copyOf(names);
    }
}
