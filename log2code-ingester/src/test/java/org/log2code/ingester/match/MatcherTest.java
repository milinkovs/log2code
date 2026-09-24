package org.log2code.ingester.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.AnalysisRun;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.ModuleInfo;
import org.log2code.core.model.TypeInfo;
import org.log2code.ingester.catalog.CatalogIndex;

/**
 * T20: every scenario its own text lists (exact match, logger-disambiguated same message, ambiguous
 * same-class same-message, dynamic template, wrong level, {@code get_class} inheritance, prefix match,
 * empty message, no catalog entry at all) plus a golden {@code score_breakdown} test, all scored against
 * the real {@code config/matching.yml} (0.10's own initial weights) and a small synthetic catalog per
 * scenario (via {@link CatalogIndex#build}, no OpenSearch).
 */
class MatcherTest {

    private static final Path REAL_CONFIG = Path.of("..", "config", "matching.yml");
    private static final MatchingConfig CONFIG = MatchingConfigLoader.load(REAL_CONFIG);
    private static final CodeUnit PROJECT = new CodeUnit(CodeUnit.TYPE_PROJECT, "matcher-fixture", "v1");
    private static final CodeVersion CODE_VERSION = new CodeVersion("matcher-fixture", "v1");
    private static final String SERVICE = "svc";

    @Test
    void exactMatchScoresHighAndReportsMatched() {
        CatalogEntry entry = entry("stmt-owner", "org.log2code.fixture.OwnerResource",
            "org.log2code.fixture.OwnerResource", "class_literal", Level.INFO, false,
            "Saving owner {}", "placeholders", false);
        Matcher matcher = matcher(List.of(entry), List.of());

        MatchResult result = matcher.match(event("org.log2code.fixture.OwnerResource", Level.INFO,
            "Saving owner Owner[id=1]", false));

        assertThat(result.status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(result.statementId()).isEqualTo("stmt-owner");
        assertThat(result.confidenceLevel()).isEqualTo(MatchResult.CONFIDENCE_HIGH);
        assertThat(result.args()).containsExactly("Owner[id=1]");
    }

    @Test
    void sameMessageInTwoClassesIsResolvedByTheLogger() {
        CatalogEntry a = entry("stmt-a", "org.log2code.fixture.A", "org.log2code.fixture.A",
            "class_literal", Level.INFO, false, "Handling request", "literal", false);
        CatalogEntry b = entry("stmt-b", "org.log2code.fixture.B", "org.log2code.fixture.B",
            "class_literal", Level.INFO, false, "Handling request", "literal", false);
        Matcher matcher = matcher(List.of(a, b), List.of());

        // logger_raw resolves EXACTLY to A alone - B is still a token-only candidate (same literal
        // message, so same constant_tokens) but its logger conflicts with the resolved name.
        MatchResult result = matcher.match(event("org.log2code.fixture.A", Level.INFO, "Handling request", false));

        assertThat(result.status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(result.statementId()).isEqualTo("stmt-a");
        assertThat(result.candidates()).extracting(c -> c.statementId()).contains("stmt-a", "stmt-b");
        assertThat(result.scoreBreakdown()).containsEntry("logger_exact", CONFIG.weights().loggerExact());
    }

    @Test
    void sameMessageInTwoMethodsOfTheSameClassIsAmbiguous() {
        // Same class (so the same logger), same template, same level, same throwable-ness - only
        // statement_id differs (0.10's CatalogKey has no method-level field: two log statements in two
        // methods of the same class are, for matching purposes, indistinguishable except by statement_id).
        CatalogEntry a = entry("stmt-a", "org.log2code.fixture.C", "org.log2code.fixture.C",
            "class_literal", Level.INFO, false, "Saving {}", "placeholders", false);
        CatalogEntry b = entry("stmt-b", "org.log2code.fixture.C", "org.log2code.fixture.C",
            "class_literal", Level.INFO, false, "Saving {}", "placeholders", false);
        Matcher matcher = matcher(List.of(a, b), List.of());

        MatchResult result = matcher.match(event("org.log2code.fixture.C", Level.INFO, "Saving thing", false));

        assertThat(result.status()).isEqualTo(MatchResult.STATUS_AMBIGUOUS);
        assertThat(result.statementId()).isEqualTo("stmt-a"); // tie-break: lower statement_id wins
        assertThat(result.confidence()).isCloseTo(
            result.candidates().get(0).score() * CONFIG.thresholds().ambiguousPenalty(), within(1e-9));
    }

    @Test
    void dynamicTemplateSkipsRegexAndSpecificityEntirely() {
        CatalogEntry entry = entry("stmt-dyn", "org.log2code.fixture.Dyn", "org.log2code.fixture.Dyn",
            "class_literal", Level.INFO, false, "{}", "dynamic", false);
        Matcher matcher = matcher(List.of(entry), List.of());

        MatchResult result = matcher.match(event("org.log2code.fixture.Dyn", Level.INFO,
            "whatever text shows up here", false));

        assertThat(result.scoreBreakdown()).doesNotContainKeys("regex_full", "regex_prefix", "specificity");
        // 0.10: a dynamic template can score at most logger_exact + level_equal + throwable_consistent = 0.35.
        assertThat(result.confidence()).isCloseTo(0.35, within(1e-6));
        assertThat(result.status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(result.args()).containsExactly("whatever text shows up here");
    }

    @Test
    void wrongLevelLosesToTheLevelConsistentCandidate() {
        CatalogEntry infoEntry = entry("stmt-info", "org.log2code.fixture.D", "org.log2code.fixture.D",
            "class_literal", Level.INFO, false, "Connection reset", "literal", false);
        CatalogEntry errorEntry = entry("stmt-error", "org.log2code.fixture.D", "org.log2code.fixture.D",
            "class_literal", Level.ERROR, false, "Connection reset", "literal", false);
        Matcher matcher = matcher(List.of(infoEntry, errorEntry), List.of());

        MatchResult result = matcher.match(event("org.log2code.fixture.D", Level.INFO, "Connection reset", false));

        assertThat(result.status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(result.statementId()).isEqualTo("stmt-info");
        Map<String, Double> breakdown = result.scoreBreakdown();
        assertThat(breakdown).containsEntry("level_equal", CONFIG.weights().levelEqual());
        assertThat(breakdown).doesNotContainKey("level_conflict");
    }

    @Test
    void getClassInheritanceScoresLoggerHierarchy() {
        String abstractClass = "org.log2code.fixture.AbstractResource";
        String concreteClass = "org.log2code.fixture.OwnerResource";
        CatalogEntry entry = entry("stmt-abstract", abstractClass, null, "get_class", Level.INFO, false,
            "Handling {}", "placeholders", false);
        TypeInfo abstractType = new TypeInfo("type-abstract", PROJECT, "matcher-fixture-mod", "AbstractResource.java",
            abstractClass, abstractClass, null, List.of(), "class");
        TypeInfo concreteType = new TypeInfo("type-owner", PROJECT, "matcher-fixture-mod", "OwnerResource.java",
            concreteClass, concreteClass, abstractClass, List.of(), "class");
        Matcher matcher = matcher(List.of(entry), List.of(abstractType, concreteType));

        // logger_raw is the CONCRETE subclass (getClass() at runtime) - resolves exactly to itself, then
        // byLogger's get_class branch climbs to the abstract statement via ancestors().
        MatchResult result = matcher.match(event(concreteClass, Level.INFO, "Handling request", false));

        assertThat(result.status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(result.statementId()).isEqualTo("stmt-abstract");
        assertThat(result.scoreBreakdown()).containsEntry("logger_hierarchy", CONFIG.weights().loggerHierarchy());
        assertThat(result.scoreBreakdown()).doesNotContainKeys("logger_exact", "logger_abbrev_multi");
    }

    @Test
    void messageWithExtraTrailingTextMatchesOnlyAsAPrefix() {
        CatalogEntry entry = entry("stmt-shutdown", "org.log2code.fixture.E", "org.log2code.fixture.E",
            "class_literal", Level.INFO, false, "Shutting down", "literal", false);
        Matcher matcher = matcher(List.of(entry), List.of());

        MatchResult result = matcher.match(event("org.log2code.fixture.E", Level.INFO,
            "Shutting down gracefully, goodbye", false));

        assertThat(result.scoreBreakdown()).containsEntry("regex_prefix", CONFIG.weights().regexPrefix());
        assertThat(result.scoreBreakdown()).doesNotContainKey("regex_full");
        assertThat(result.statementId()).isEqualTo("stmt-shutdown");
    }

    @Test
    void emptyMessageDiscardsAnyNonDynamicCandidateAndYieldsUnmatched() {
        CatalogEntry entry = entry("stmt-empty", "org.log2code.fixture.F", "org.log2code.fixture.F",
            "class_literal", Level.INFO, false, "Saving owner {}", "placeholders", false);
        Matcher matcher = matcher(List.of(entry), List.of());

        MatchResult result = matcher.match(event("org.log2code.fixture.F", Level.INFO, "", false));

        assertThat(result.status()).isEqualTo(MatchResult.STATUS_UNMATCHED);
        assertThat(result.statementId()).isNull();
        assertThat(result.confidence()).isNull();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.scoreBreakdown()).isEmpty();
    }

    @Test
    void noApplicableStatementAtAllYieldsUnmatched() {
        CatalogEntry entry = entry("stmt-unrelated", "org.log2code.fixture.G", "org.log2code.fixture.G",
            "class_literal", Level.INFO, false, "Saving owner {}", "placeholders", false);
        Matcher matcher = matcher(List.of(entry), List.of());

        // Logger unknown to N(s) and a message with no tokens shared with any catalog entry.
        MatchResult result = matcher.match(event("com.unrelated.NoSuchLogger", Level.INFO,
            "zzz qux flibbertigibbet", false));

        assertThat(result.status()).isEqualTo(MatchResult.STATUS_UNMATCHED);
        assertThat(result.statementId()).isNull();
    }

    @Test
    void goldenScoreBreakdownForAKnownScenario() {
        // literal_length("Ping ") = 4 ("Ping", the trailing space before the hole is whitespace).
        CatalogEntry entry = entry("stmt-ping", "org.log2code.fixture.Ping", "org.log2code.fixture.Ping",
            "class_literal", Level.INFO, false, "Ping {}", "placeholders", false);
        Matcher matcher = matcher(List.of(entry), List.of());

        MatchResult result = matcher.match(event("org.log2code.fixture.Ping", Level.INFO, "Ping pong", false));

        MatchingConfig.Weights w = CONFIG.weights();
        double expectedSpecificity = w.specificityMax() * 4.0 / (4.0 + w.specificityK());
        double expectedScore = w.regexFull() + expectedSpecificity + w.loggerExact() + w.levelEqual() + w.throwableConsistent();

        assertThat(result.scoreBreakdown()).containsOnlyKeys(
            "regex_full", "specificity", "logger_exact", "level_equal", "throwable_consistent");
        assertThat(result.scoreBreakdown().get("regex_full")).isEqualTo(w.regexFull());
        assertThat(result.scoreBreakdown().get("logger_exact")).isEqualTo(w.loggerExact());
        assertThat(result.scoreBreakdown().get("level_equal")).isEqualTo(w.levelEqual());
        assertThat(result.scoreBreakdown().get("throwable_consistent")).isEqualTo(w.throwableConsistent());
        assertThat(result.scoreBreakdown().get("specificity")).isCloseTo(expectedSpecificity, within(1e-9));
        assertThat(result.confidence()).isCloseTo(expectedScore, within(1e-9));
        assertThat(result.status()).isEqualTo(MatchResult.STATUS_MATCHED);
    }

    @Test
    void throwablePresenceAffectsScoreConsistently() {
        CatalogEntry withThrowable = entry("stmt-err", "org.log2code.fixture.H", "org.log2code.fixture.H",
            "class_literal", Level.ERROR, false, "Boom", "literal", true);
        Matcher matcher = matcher(List.of(withThrowable), List.of());

        MatchResult withException = matcher.match(event("org.log2code.fixture.H", Level.ERROR, "Boom", true));
        MatchResult withoutException = matcher.match(event("org.log2code.fixture.H", Level.ERROR, "Boom", false));

        assertThat(withException.scoreBreakdown()).containsEntry("throwable_consistent", CONFIG.weights().throwableConsistent());
        assertThat(withoutException.scoreBreakdown()).containsEntry("throwable_inconsistent", CONFIG.weights().throwableInconsistent());
        assertThat(withException.confidence()).isGreaterThan(withoutException.confidence());
    }

    @Test
    void explainRendersResolutionCandidatesAndDecision() {
        CatalogEntry entry = entry("stmt-owner", "org.log2code.fixture.OwnerResource",
            "org.log2code.fixture.OwnerResource", "class_literal", Level.INFO, false,
            "Saving owner {}", "placeholders", false);
        Matcher matcher = matcher(List.of(entry), List.of());

        String explanation = matcher.explain(event("org.log2code.fixture.OwnerResource", Level.INFO,
            "Saving owner Owner[id=1]", false));

        assertThat(explanation).contains("resolution:").contains("candidates").contains("decision: status=matched")
            .contains("stmt-owner");
    }

    private static Matcher matcher(List<CatalogEntry> entries, List<TypeInfo> types) {
        AnalysisRun run = new AnalysisRun("run-1", CodeUnit.TYPE_PROJECT, PROJECT,
            "https://example.invalid/matcher-fixture", "test-analyzer",
            Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:05Z"), 1000, Map.of(),
            List.of(new ModuleInfo("matcher-fixture-mod", SERVICE, List.of("src/main/java"), List.of(), List.of())));
        CatalogIndex index = CatalogIndex.build(run, entries, types);
        return new Matcher(index, CONFIG);
    }

    private static LogEvent event(String loggerRaw, Level level, String message, boolean hasException) {
        ExceptionInfo exception = hasException
            ? new ExceptionInfo("java.lang.RuntimeException", "java.lang.RuntimeException", "boom", List.of(), List.of())
            : null;
        return new LogEvent("ds", "svc.log", 1, 1, 0, Instant.parse("2026-09-24T10:00:00Z"), null,
            SERVICE, "matcher-fixture-mod", null, null, null, level, loggerRaw, null, message, message,
            null, null, exception, CODE_VERSION, null, "spring-boot-default");
    }

    private static CatalogEntry entry(String statementId, String classFqn, String loggerName, String loggerNameKind,
                                       Level level, boolean levelDynamic, String template, String templateKind,
                                       boolean hasThrowableArg) {
        return new CatalogEntry(
            statementId, statementId + "-logical", PROJECT, "matcher-fixture-mod", SERVICE,
            "Fixture.java", "file-" + statementId, "pkg", classFqn, classFqn,
            "run", "run()", null, false,
            10, 10, 4, 5, 15,
            "slf4j", "typed", "log", loggerName, loggerNameKind,
            level, levelDynamic,
            "\"" + template + "\"", template, templateKind, null, null, List.of(), template.length(), 0, hasThrowableArg,
            new EnclosingBlock("method", null, null, 5, 15), null,
            "    log.info(...);\n", 10,
            null, "test-analyzer", Instant.parse("2026-09-24T10:00:00Z"));
    }
}
