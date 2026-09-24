package org.log2code.ingester.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.log2code.core.logger.LoggerNameMatcher;

/**
 * Resolves an observed {@code logger_raw} string to the set of known names it plausibly denotes (0.10
 * step 1), for one service's {@code N(s)}: {@code logger_name} from applicable catalog entries plus
 * {@code class_fqn}/{@code class_binary} from applicable {@code log2code-types} (T19 step 1/3).
 *
 * <p>Classification per candidate name reuses {@link LoggerNameMatcher} (moved to {@code log2code-core}
 * for exactly this reuse, ADR-020): {@code exact} wins outright (0.10 defines it as plain set membership,
 * checked directly here rather than through {@link LoggerNameMatcher#classify}, which would also accept
 * it but only after the same equality check); otherwise every candidate is classified independently and
 * the highest-priority kind that matched anything (abbreviated, then truncated) determines the result,
 * collecting every candidate that reached that kind into {@code names} - {@code abbrev_unique} vs.
 * {@code abbrev_multi} is exactly this set's size.
 */
public final class LoggerResolver {

    /** {@code kind}: {@code exact | abbrev_unique | abbrev_multi | truncated | unknown} (T19 step 3). */
    public enum Kind {
        EXACT, ABBREV_UNIQUE, ABBREV_MULTI, TRUNCATED, UNKNOWN
    }

    public record Resolution(Kind kind, Set<String> names) {
        private static final Resolution UNKNOWN = new Resolution(Kind.UNKNOWN, Set.of());
    }

    private final Map<String, Set<String>> namesByService;
    private final Map<String, Resolution> cache = new ConcurrentHashMap<>();

    LoggerResolver(Map<String, Set<String>> namesByService) {
        this.namesByService = Map.copyOf(namesByService);
    }

    public Resolution resolve(String loggerRaw, String service) {
        if (loggerRaw == null || loggerRaw.isBlank() || service == null) {
            return Resolution.UNKNOWN;
        }
        return cache.computeIfAbsent(cacheKey(service, loggerRaw), key -> doResolve(loggerRaw, service));
    }

    private Resolution doResolve(String loggerRaw, String service) {
        Set<String> names = namesByService.get(service);
        if (names == null || names.isEmpty()) {
            return Resolution.UNKNOWN;
        }
        if (names.contains(loggerRaw)) {
            return new Resolution(Kind.EXACT, Set.of(loggerRaw));
        }

        List<String> abbreviated = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        for (String candidate : names) {
            switch (LoggerNameMatcher.classify(loggerRaw, candidate)) {
                case ABBREVIATED -> abbreviated.add(candidate);
                case TRUNCATED -> truncated.add(candidate);
                default -> { }
            }
        }
        if (!abbreviated.isEmpty()) {
            Kind kind = abbreviated.size() == 1 ? Kind.ABBREV_UNIQUE : Kind.ABBREV_MULTI;
            return new Resolution(kind, new LinkedHashSet<>(abbreviated));
        }
        if (!truncated.isEmpty()) {
            return new Resolution(Kind.TRUNCATED, new LinkedHashSet<>(truncated));
        }
        return Resolution.UNKNOWN;
    }

    private static String cacheKey(String service, String loggerRaw) {
        return service + '\u0000' + loggerRaw;
    }
}
