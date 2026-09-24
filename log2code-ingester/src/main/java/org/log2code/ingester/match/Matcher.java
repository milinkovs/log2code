package org.log2code.ingester.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.MatchResult;
import org.log2code.core.template.MessageTemplate;
import org.log2code.core.template.Tokenizer;
import org.log2code.ingester.catalog.CatalogIndex;
import org.log2code.ingester.catalog.CatalogIndex.LoggerMatchReason;
import org.log2code.ingester.catalog.CatalogKey;
import org.log2code.ingester.catalog.LoggerResolver.Kind;
import org.log2code.ingester.catalog.LoggerResolver.Resolution;

/**
 * Matches one {@link LogEvent} against the in-memory catalog ({@link CatalogIndex}, T19), implementing
 * 0.10 in full: candidate generation (step 2), scoring (step 3) and the matched/ambiguous/unmatched
 * decision (step 4). {@link #match(LogEvent)} and {@link #explain(LogEvent)} share one evaluation per
 * event, cached (T20 step 3) in an {@link LruCache} of 100k entries keyed on the five fields 0.10 step 3
 * actually uses: {@code (service, logger_raw, level, message, hasException)}.
 *
 * <p>{@link MatchResult}'s denormalized {@code match.*} fields ({@code code_unit}, {@code module},
 * {@code class_fqn}, {@code method_name}, {@code file_path}, {@code logging_api}, {@code template_kind},
 * {@code line}, {@code template}, {@code github_url}) are left {@code null} here: {@link CatalogKey}
 * (T19's ADR-020) intentionally keeps only the fields 0.10 matching itself needs, not the full ~40-field
 * catalog document. The ingester (T21), which does have the winning statement's full {@code CatalogEntry},
 * denormalizes those fields at write time (0.7: "polja `match.*` se denormalizuju iz naredbe iz kataloga").
 */
public final class Matcher {

    private static final String TEMPLATE_KIND_DYNAMIC = "dynamic";

    private final CatalogIndex index;
    private final MatchingConfig config;
    private final LruCache<CacheKey, Outcome> cache = new LruCache<>(100_000);

    public Matcher(CatalogIndex index, MatchingConfig config) {
        this.index = Objects.requireNonNull(index, "index");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** 0.10 in full: candidates, scoring, decision. Cached per event (see the class javadoc). */
    public MatchResult match(LogEvent event) {
        return toMatchResult(evaluate(event));
    }

    /** A human-readable rendering of the same evaluation {@link #match(LogEvent)} would produce: resolution, every scored candidate with its {@code score_breakdown}, and the final decision. */
    public String explain(LogEvent event) {
        Outcome outcome = evaluate(event);
        StringBuilder sb = new StringBuilder();
        sb.append("event: service=").append(event.service())
            .append(" logger_raw=").append(event.loggerRaw())
            .append(" level=").append(event.level())
            .append(" hasException=").append(event.exception() != null)
            .append(" message=").append(event.message())
            .append('\n');
        sb.append("resolution: kind=").append(outcome.resolution().kind())
            .append(" names=").append(outcome.resolution().names())
            .append('\n');
        if (outcome.scored().isEmpty()) {
            sb.append("candidates: none\n");
        } else {
            sb.append("candidates (").append(outcome.scored().size()).append("):\n");
            for (Scored s : outcome.scored()) {
                sb.append("  ").append(s.key().statementId())
                    .append(" score=").append(format(s.score()))
                    .append(' ').append(s.breakdown())
                    .append('\n');
            }
        }
        Decision decision = outcome.decision();
        sb.append("decision: status=").append(decision.status());
        if (decision.winner() != null) {
            sb.append(" statement_id=").append(decision.winner().key().statementId())
                .append(" confidence=").append(format(decision.confidence()));
        }
        return sb.toString();
    }

    private Outcome evaluate(LogEvent event) {
        CacheKey key = new CacheKey(event.service(), event.loggerRaw(), event.level(),
            event.message(), event.exception() != null);
        return cache.computeIfAbsent(key, k -> compute(event));
    }

    private Outcome compute(LogEvent event) {
        String service = event.service();
        String message = event.message() == null ? "" : event.message();
        Resolution resolution = index.loggerResolver().resolve(event.loggerRaw(), service);

        List<CatalogKey> byLogger = index.byLogger(resolution.names(), service);
        List<String> messageTokens = Tokenizer.tokens(message);
        List<CatalogKey> byTokens = index.byTokens(messageTokens, service, config.candidates().topKTokens());

        Set<CatalogKey> pool = new LinkedHashSet<>(byLogger);
        pool.addAll(byTokens);
        int maxCandidates = config.candidates().maxCandidates();
        List<CatalogKey> capped = pool.size() <= maxCandidates ? List.copyOf(pool) : firstN(pool, maxCandidates);

        List<Scored> scored = new ArrayList<>();
        for (CatalogKey candidate : capped) {
            score(event, message, resolution, candidate).ifPresent(scored::add);
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
            .thenComparing(s -> s.key().statementId()));

        return new Outcome(resolution, List.copyOf(scored), decide(scored));
    }

    /** 0.10 step 3, for one candidate. Empty when the candidate has no regex match and is not {@code dynamic} (discarded). */
    private Optional<Scored> score(LogEvent event, String message, Resolution resolution, CatalogKey key) {
        MessageTemplate template = key.template();
        boolean dynamic = TEMPLATE_KIND_DYNAMIC.equals(key.templateKind());

        Optional<List<String>> fullMatch = template.matchFull(message);
        Optional<List<String>> prefixMatch = fullMatch.isPresent() ? Optional.empty() : template.matchPrefix(message);
        if (fullMatch.isEmpty() && prefixMatch.isEmpty() && !dynamic) {
            return Optional.empty();
        }

        Map<String, Double> breakdown = new LinkedHashMap<>();
        List<String> args;
        if (!dynamic) {
            if (fullMatch.isPresent()) {
                breakdown.put("regex_full", config.weights().regexFull());
                args = fullMatch.get();
            } else {
                breakdown.put("regex_prefix", config.weights().regexPrefix());
                args = prefixMatch.get();
            }
            double specificity = config.weights().specificityMax() * template.literalLength()
                / (template.literalLength() + config.weights().specificityK());
            breakdown.put("specificity", specificity);
        } else {
            args = fullMatch.orElseGet(() -> prefixMatch.orElse(List.of()));
        }

        scoreLogger(key, resolution, breakdown);
        scoreLevel(event, key, breakdown);
        scoreThrowable(event, key, breakdown);

        double raw = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
        double score = Math.max(0.0, Math.min(1.0, raw));
        return Optional.of(new Scored(key, score, Map.copyOf(breakdown), args));
    }

    /** 0.10 step 3's logger family: {@code logger_exact}/{@code logger_hierarchy}/{@code logger_abbrev_multi}/{@code logger_conflict} are mutually exclusive per candidate. */
    private void scoreLogger(CatalogKey key, Resolution resolution, Map<String, Double> breakdown) {
        LoggerMatchReason reason = index.loggerMatchReason(key, resolution.names());
        switch (reason) {
            case HIERARCHY -> breakdown.put("logger_hierarchy", config.weights().loggerHierarchy());
            case DIRECT -> {
                if (resolution.names().size() == 1) {
                    breakdown.put("logger_exact", config.weights().loggerExact());
                } else {
                    breakdown.put("logger_abbrev_multi", config.weights().loggerAbbrevMulti());
                }
            }
            case NONE -> {
                if (resolution.kind() != Kind.UNKNOWN) {
                    breakdown.put("logger_conflict", config.weights().loggerConflict());
                }
            }
        }
    }

    /** 0.10 step 3's level family: {@code level_dynamic}/{@code level_equal}/{@code level_conflict} are mutually exclusive per candidate (a dynamic-level statement's own {@code level} is always {@code UNKNOWN}, so it is judged only as dynamic, never compared). */
    private void scoreLevel(LogEvent event, CatalogKey key, Map<String, Double> breakdown) {
        if (key.levelDynamic()) {
            breakdown.put("level_dynamic", config.weights().levelDynamic());
        } else if (Level.compatible(key.level(), event.level())) {
            breakdown.put("level_equal", config.weights().levelEqual());
        } else {
            breakdown.put("level_conflict", config.weights().levelConflict());
        }
    }

    private void scoreThrowable(LogEvent event, CatalogKey key, Map<String, Double> breakdown) {
        boolean hasException = event.exception() != null;
        if (hasException == key.hasThrowableArg()) {
            breakdown.put("throwable_consistent", config.weights().throwableConsistent());
        } else {
            breakdown.put("throwable_inconsistent", config.weights().throwableInconsistent());
        }
    }

    /** 0.10 step 4. */
    private Decision decide(List<Scored> scored) {
        if (scored.isEmpty()) {
            return Decision.unmatched();
        }
        Scored best = scored.get(0);
        if (best.score() < config.thresholds().minScore()) {
            return Decision.unmatched();
        }
        if (scored.size() > 1) {
            Scored second = scored.get(1);
            if (best.score() - second.score() < config.thresholds().ambiguityMargin()) {
                return new Decision(MatchResult.STATUS_AMBIGUOUS, best, best.score() * config.thresholds().ambiguousPenalty());
            }
        }
        return new Decision(MatchResult.STATUS_MATCHED, best, best.score());
    }

    private MatchResult toMatchResult(Outcome outcome) {
        List<Candidate> top5 = outcome.scored().stream()
            .limit(5)
            .map(s -> new Candidate(s.key().statementId(), s.score()))
            .toList();

        Decision decision = outcome.decision();
        boolean matched = decision.winner() != null;
        return new MatchResult(
            decision.status(),
            matched ? decision.winner().key().statementId() : null,
            matched ? decision.confidence() : null,
            matched ? confidenceLevel(decision.confidence()) : null,
            top5,
            matched ? decision.winner().breakdown() : Map.of(),
            matched ? decision.winner().args() : List.of(),
            null, null, null, null, null, null, null, null, null, null);
    }

    private String confidenceLevel(double confidence) {
        if (confidence >= config.thresholds().high()) {
            return MatchResult.CONFIDENCE_HIGH;
        }
        if (confidence >= config.thresholds().medium()) {
            return MatchResult.CONFIDENCE_MEDIUM;
        }
        return MatchResult.CONFIDENCE_LOW;
    }

    private static List<CatalogKey> firstN(Iterable<CatalogKey> pool, int n) {
        List<CatalogKey> result = new ArrayList<>(n);
        for (CatalogKey key : pool) {
            if (result.size() >= n) {
                break;
            }
            result.add(key);
        }
        return result;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private record CacheKey(String service, String loggerRaw, Level level, String message, boolean hasException) {
    }

    private record Scored(CatalogKey key, double score, Map<String, Double> breakdown, List<String> args) {
    }

    private record Decision(String status, Scored winner, Double confidence) {
        static Decision unmatched() {
            return new Decision(MatchResult.STATUS_UNMATCHED, null, null);
        }
    }

    private record Outcome(Resolution resolution, List<Scored> scored, Decision decision) {
    }
}
