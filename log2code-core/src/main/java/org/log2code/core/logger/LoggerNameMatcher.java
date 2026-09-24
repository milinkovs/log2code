package org.log2code.core.logger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The abbreviated/truncated logger-name matching rule from 0.10 step 1: classifies how (if at all) an
 * observed {@code logger_raw} string plausibly names a given fully-qualified class. In {@code log2code-core}
 * (not {@code log2code-analyzer}, where it originated) because it is shared by two independent callers:
 * T12's {@code DependencySelector} (analyzer module, a heuristic driving automatic dependency selection,
 * where a small number of false positives/negatives is acceptable - AC4: 90%) and T19's {@code LoggerResolver}
 * (ingester module, the real matcher's per-candidate classification, run against every name in a service's
 * {@code N(s)} - ADR-020).
 *
 * <p>{@code exact}/{@code abbreviated} follow 0.10 literally. {@code truncated} is an internal interpretation
 * (0.13: implementation detail, not part of the algorithm's schema/weights): rather than reconstruct
 * Logback's exact {@code %logger{39}} abbreviation state before the left-truncation to 40 characters, this
 * checks whether {@code logger_raw} is a trailing substring of either the full FQN or its "maximally
 * abbreviated" form (every package segment reduced to its first character; the class name itself is never
 * abbreviated by Logback). Both truncation examples in {@code docs/log-format.md}
 * ({@code .w.s.m.s.DefaultHandlerExceptionResolver} and {@code trationDelegate$BeanPostProcessorChecker})
 * match under this rule.
 */
public final class LoggerNameMatcher {

    /** Below this length, a truncated (suffix) match is too ambiguous to trust. */
    private static final int MIN_TRUNCATED_LENGTH = 10;

    // Valid Java identifier characters plus '.' (package/nesting separator). Rules out logger names that
    // structurally cannot be a class FQN under any abbreviation/truncation, such as Tomcat's container
    // hierarchy names (e.g. "C.[Tomcat].[localhost].[/]") which embed '[' ']'.
    private static final Pattern CLASS_NAME_SHAPED = Pattern.compile("[\\p{L}\\p{N}_$.]+");

    public enum Kind { EXACT, ABBREVIATED, TRUNCATED, NONE }

    private LoggerNameMatcher() {
    }

    /**
     * Whether {@code loggerRaw} could possibly denote a Java class name (only identifier characters and
     * dots) as opposed to a synthetic logger category, e.g. Tomcat's {@code Catalina.[Tomcat].[localhost]}
     * hierarchical container names. Loggers that fail this check can never match under any of the three
     * 0.10-step-1 rules, so they are excluded from the auto-select "unique loggers" universe entirely
     * (T12 AC4), rather than counted as failed matches.
     */
    public static boolean looksLikeClassName(String loggerRaw) {
        return loggerRaw != null && !loggerRaw.isBlank() && CLASS_NAME_SHAPED.matcher(loggerRaw).matches();
    }

    public static Kind classify(String loggerRaw, String candidateFqn) {
        if (loggerRaw == null || loggerRaw.isBlank() || candidateFqn == null || candidateFqn.isBlank()) {
            return Kind.NONE;
        }
        if (loggerRaw.equals(candidateFqn)) {
            return Kind.EXACT;
        }
        List<String> fqnSegments = List.of(candidateFqn.split("\\.", -1));
        if (isAbbreviated(List.of(loggerRaw.split("\\.", -1)), fqnSegments)) {
            return Kind.ABBREVIATED;
        }
        if (loggerRaw.length() >= MIN_TRUNCATED_LENGTH && isTruncated(loggerRaw, fqnSegments)) {
            return Kind.TRUNCATED;
        }
        return Kind.NONE;
    }

    public static boolean matches(String loggerRaw, String candidateFqn) {
        return classify(loggerRaw, candidateFqn) != Kind.NONE;
    }

    private static boolean isAbbreviated(List<String> rawSegments, List<String> fqnSegments) {
        if (rawSegments.isEmpty() || rawSegments.size() != fqnSegments.size()) {
            return false;
        }
        int last = rawSegments.size() - 1;
        if (!rawSegments.get(last).equals(fqnSegments.get(last))) {
            return false;
        }
        for (int i = 0; i < last; i++) {
            String rawSeg = rawSegments.get(i);
            if (rawSeg.isEmpty() || !fqnSegments.get(i).startsWith(rawSeg)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTruncated(String loggerRaw, List<String> fqnSegments) {
        String full = String.join(".", fqnSegments);
        if (full.endsWith(loggerRaw)) {
            return true;
        }
        return fullyAbbreviate(fqnSegments).endsWith(loggerRaw);
    }

    private static String fullyAbbreviate(List<String> fqnSegments) {
        if (fqnSegments.size() <= 1) {
            return String.join(".", fqnSegments);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fqnSegments.size() - 1; i++) {
            String seg = fqnSegments.get(i);
            if (!seg.isEmpty()) {
                sb.append(seg.charAt(0));
            }
            sb.append('.');
        }
        sb.append(fqnSegments.get(fqnSegments.size() - 1));
        return sb.toString();
    }
}
