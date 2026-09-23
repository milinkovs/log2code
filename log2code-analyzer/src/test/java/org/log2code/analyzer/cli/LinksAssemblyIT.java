package org.log2code.analyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.log2code.core.github.CodeUnitsConfig;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.Level;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T15 step 4, against a real OpenSearch: {@code links --update} ({@link LinksUpdateRunner})
 * recomputes {@code github_url} for already-written {@code log2code-catalog} documents and is
 * idempotent, and {@code links --verify} ({@link LinksVerifyRunner}) samples and reports against a
 * fake {@link LinksVerifyRunner.UrlChecker} (no real HTTP calls in the automated test suite).
 */
@Testcontainers
class LinksAssemblyIT {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:2.19.0")
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static OpenSearchClient client;

    @BeforeAll
    static void setUpClient() {
        client = OpenSearchClientFactory.create(OpenSearchConfig.of(OPENSEARCH.getHttpHostAddress()));
    }

    @AfterAll
    static void tearDownClient() throws IOException {
        OpenSearchClientFactory.close(client);
    }

    @Test
    void updateFillsInGithubUrlAndIsIdempotentOnASecondRun() throws IOException {
        IndexNames names = freshIndexNames();
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, "example-project", "abc123");
        CatalogEntry entry = entry("stmt-1", codeUnit, "A.java", 10, 10, null);
        CatalogWriter.writeToOpenSearch(client, names, codeUnit, List.of(entry), List.of(), List.of(), List.of());
        new IndexManager(client, names).refresh(names.catalog());

        GithubLinker linker = new GithubLinker(new CodeUnitsConfig(
            Map.of("example-project", new CodeUnitsConfig.ProjectMapping(
                "https://github.com/example/example-project", "{version}", "{file_path}")),
            List.of()), null);

        LinksUpdateRunner.Result firstRun = LinksUpdateRunner.run(client, names, linker);
        assertThat(firstRun.total()).isEqualTo(1);
        assertThat(firstRun.changed()).isEqualTo(1);
        assertThat(firstRun.linked()).isEqualTo(1);

        new IndexManager(client, names).refresh(names.catalog());
        CatalogEntry fetched = new DocumentReader(client).get(names.catalog(), "stmt-1", CatalogEntry.class);
        assertThat(fetched.githubUrl()).isEqualTo("https://github.com/example/example-project/blob/abc123/A.java#L10");

        // Second run: github_url is already correct, so nothing should be rewritten.
        LinksUpdateRunner.Result secondRun = LinksUpdateRunner.run(client, names, linker);
        assertThat(secondRun.changed()).isZero();
        assertThat(secondRun.linked()).isEqualTo(1);
    }

    @Test
    void verifyReportsTheSuccessRateAndExplainsFailures() throws IOException {
        IndexNames names = freshIndexNames();
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, "example-project-2", "def456");
        CatalogEntry linkedOk = entry("stmt-ok", codeUnit, "Ok.java", 1, 1, "https://github.com/example/repo/blob/def456/Ok.java#L1");
        CatalogEntry linkedBroken = entry("stmt-broken", codeUnit, "Broken.java", 2, 2, "https://github.com/example/repo/blob/def456/Broken.java#L2");
        CatalogEntry unlinked = entry("stmt-null", codeUnit, "NoLink.java", 3, 3, null);
        CatalogWriter.writeToOpenSearch(client, names, codeUnit, List.of(linkedOk, linkedBroken, unlinked), List.of(), List.of(), List.of());
        new IndexManager(client, names).refresh(names.catalog());

        LinksVerifyRunner.UrlChecker checker = url -> url.contains("Broken")
            ? 404
            : 200;

        LinksVerifyRunner.Result result = LinksVerifyRunner.run(client, names, 10, checker);

        // Only the two entries with a non-null github_url are sampled/checked - the unlinked one never is.
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.ok()).isEqualTo(1);
        assertThat(result.checks()).extracting(LinksVerifyRunner.CheckResult::statementId)
            .containsExactlyInAnyOrder("stmt-ok", "stmt-broken");
        assertThat(result.checks().stream().filter(c -> !c.ok()).findFirst().orElseThrow().status()).isEqualTo(404);
    }

    @Test
    void verifySampleSizeCapsHowManyLinksAreChecked() throws IOException {
        IndexNames names = freshIndexNames();
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_PROJECT, "example-project-3", "ghi789");
        List<CatalogEntry> entries = List.of(
            entry("s1", codeUnit, "A.java", 1, 1, "https://github.com/example/repo/blob/ghi789/A.java#L1"),
            entry("s2", codeUnit, "B.java", 2, 2, "https://github.com/example/repo/blob/ghi789/B.java#L2"),
            entry("s3", codeUnit, "C.java", 3, 3, "https://github.com/example/repo/blob/ghi789/C.java#L3"));
        CatalogWriter.writeToOpenSearch(client, names, codeUnit, entries, List.of(), List.of(), List.of());
        new IndexManager(client, names).refresh(names.catalog());

        AtomicInteger calls = new AtomicInteger();
        LinksVerifyRunner.UrlChecker checker = url -> {
            calls.incrementAndGet();
            return 200;
        };

        LinksVerifyRunner.Result result = LinksVerifyRunner.run(client, names, 2, checker);

        assertThat(result.total()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    private static IndexNames freshIndexNames() {
        return new IndexNames("it-" + UUID.randomUUID() + "-");
    }

    private static CatalogEntry entry(String statementId, CodeUnit codeUnit, String filePath, int line, int endLine, String githubUrl) {
        return new CatalogEntry(
            statementId, statementId, codeUnit, "module-a", "service-a",
            filePath, "file-" + statementId, "pkg", "pkg.A", "pkg.A",
            "run", "run()", null, false,
            line, endLine, 4, line, line + 5,
            "slf4j", "typed", "log", "pkg.A", "class_literal",
            Level.INFO, false,
            "\"hi\"", "hi", "literal", null, null, List.of(), 2, 0, false,
            new EnclosingBlock("method", null, null, line, line + 5), null,
            "    log.info(\"hi\");\n", line,
            githubUrl, "test-analyzer", Instant.parse("2026-09-23T10:00:00Z"));
    }
}
