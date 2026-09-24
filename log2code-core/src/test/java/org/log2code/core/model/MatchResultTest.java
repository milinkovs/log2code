package org.log2code.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T21: {@code withDenormalizedFrom} fills in the {@code match.*} fields T20 deliberately leaves {@code null}. */
class MatchResultTest {

    @Test
    void withDenormalizedFromFillsInTheDenormalizedFieldsAndLeavesTheRestUntouched() {
        MatchResult matched = new MatchResult(
            MatchResult.STATUS_MATCHED, "stmt-1", 0.9, MatchResult.CONFIDENCE_HIGH,
            List.of(new Candidate("stmt-1", 0.9)), Map.of("regex_full", 0.45), List.of("42"),
            null, null, null, null, null, null, null, null, null, null);
        CatalogEntry entry = minimalEntry();

        MatchResult denormalized = matched.withDenormalizedFrom(entry);

        assertThat(denormalized.codeUnit()).isEqualTo("example");
        assertThat(denormalized.module()).isEqualTo("module-a");
        assertThat(denormalized.classFqn()).isEqualTo("pkg.A");
        assertThat(denormalized.methodName()).isEqualTo("run");
        assertThat(denormalized.filePath()).isEqualTo("A.java");
        assertThat(denormalized.loggingApi()).isEqualTo("slf4j");
        assertThat(denormalized.templateKind()).isEqualTo("literal");
        assertThat(denormalized.line()).isEqualTo(10);
        assertThat(denormalized.template()).isEqualTo("hi");
        assertThat(denormalized.githubUrl()).isEqualTo("https://github.com/example/repo/blob/v1/A.java#L10");
        // The matching-outcome fields themselves are untouched.
        assertThat(denormalized.status()).isEqualTo(MatchResult.STATUS_MATCHED);
        assertThat(denormalized.statementId()).isEqualTo("stmt-1");
        assertThat(denormalized.confidence()).isEqualTo(0.9);
        assertThat(denormalized.confidenceLevel()).isEqualTo(MatchResult.CONFIDENCE_HIGH);
        assertThat(denormalized.candidates()).extracting(Candidate::statementId).containsExactly("stmt-1");
        assertThat(denormalized.scoreBreakdown()).containsEntry("regex_full", 0.45);
        assertThat(denormalized.args()).containsExactly("42");
    }

    private static CatalogEntry minimalEntry() {
        return new CatalogEntry(
            "stmt-1", "logical-1",
            new CodeUnit(CodeUnit.TYPE_PROJECT, "example", "v1"),
            "module-a", "service-a",
            "A.java", "file-1", "pkg", "pkg.A", "pkg.A",
            "run", "run()", null, false,
            10, 10, 4, 5, 15,
            "slf4j", "typed", "log", "pkg.A", "class_literal",
            Level.INFO, false,
            "\"hi\"", "hi", "literal", null, null, List.of(), 2, 0, false,
            new EnclosingBlock("method", null, null, 5, 15), null,
            "    log.info(\"hi\");\n", 10,
            "https://github.com/example/repo/blob/v1/A.java#L10", "0.1.0-SNAPSHOT", Instant.parse("2026-09-23T10:00:00Z"));
    }
}
