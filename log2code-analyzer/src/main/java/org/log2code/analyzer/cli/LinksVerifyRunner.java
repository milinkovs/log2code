package org.log2code.analyzer.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;

/**
 * {@code links --verify [--sample N]} (T15 step 4/AC3): picks a random sample of {@code N}
 * {@code log2code-catalog} documents that have a {@code github_url} and checks each with a real
 * HTTP request, expecting status 200. {@link UrlChecker} is injected so the sampling/reporting logic
 * can be tested without real network calls; {@link #realHttpChecker()} is what {@link LinksCommand}
 * actually uses.
 */
final class LinksVerifyRunner {

    private static final int STREAM_PAGE_SIZE = 500;

    private LinksVerifyRunner() {
    }

    /** Returns the HTTP status code for {@code url}, or throws on a network-level failure. */
    interface UrlChecker {
        int statusCode(String url) throws IOException;
    }

    /** One checked entry: {@code error} is {@code null} on a plain HTTP response (successful or not). */
    record CheckResult(String statementId, String url, int status, String error) {
        boolean ok() {
            return error == null && status == 200;
        }
    }

    record Result(List<CheckResult> checks) {

        long total() {
            return checks.size();
        }

        long ok() {
            return checks.stream().filter(CheckResult::ok).count();
        }

        double okRate() {
            return total() == 0 ? 1.0 : (double) ok() / total();
        }
    }

    static Result run(OpenSearchClient client, IndexNames indexNames, int sampleSize, UrlChecker checker) throws IOException {
        Query hasGithubUrl = Query.of(q -> q.exists(e -> e.field("github_url")));
        List<CatalogEntry> linked;
        try (Stream<CatalogEntry> stream =
                 new DocumentReader(client).streamAll(indexNames.catalog(), hasGithubUrl, CatalogEntry.class, STREAM_PAGE_SIZE)) {
            linked = stream.toList();
        }

        List<CatalogEntry> sample = new ArrayList<>(linked);
        Collections.shuffle(sample, new Random());
        if (sample.size() > sampleSize) {
            sample = sample.subList(0, sampleSize);
        }

        List<CheckResult> checks = new ArrayList<>();
        for (CatalogEntry entry : sample) {
            checks.add(check(entry, checker));
        }
        return new Result(checks);
    }

    private static CheckResult check(CatalogEntry entry, UrlChecker checker) {
        try {
            int status = checker.statusCode(entry.githubUrl());
            return new CheckResult(entry.statementId(), entry.githubUrl(), status, null);
        } catch (IOException e) {
            return new CheckResult(entry.statementId(), entry.githubUrl(), -1, e.getMessage());
        }
    }

    /** A real {@link UrlChecker}: HEAD request, 10s connect / 15s overall timeout, follows redirects. */
    static UrlChecker realHttpChecker() {
        HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            try {
                return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while checking " + url, e);
            }
        };
    }
}
