package org.log2code.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogEntryTest {

    @Test
    void withGithubUrlReplacesOnlyThatField() {
        CatalogEntry original = minimalEntry(null);

        CatalogEntry linked = original.withGithubUrl("https://github.com/example/repo/blob/v1/A.java#L1");

        assertThat(linked.githubUrl()).isEqualTo("https://github.com/example/repo/blob/v1/A.java#L1");
        assertThat(linked).isEqualTo(original.withGithubUrl("https://github.com/example/repo/blob/v1/A.java#L1"));
        assertThat(linked.statementId()).isEqualTo(original.statementId());
        assertThat(linked.filePath()).isEqualTo(original.filePath());
        assertThat(linked.line()).isEqualTo(original.line());
        // Every other field is untouched: swapping githubUrl back must round-trip to the exact original.
        assertThat(linked.withGithubUrl(null)).isEqualTo(original);
    }

    private static CatalogEntry minimalEntry(String githubUrl) {
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
            githubUrl, "0.1.0-SNAPSHOT", Instant.parse("2026-09-23T10:00:00Z"));
    }
}
