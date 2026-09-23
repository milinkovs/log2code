package org.log2code.analyzer.cli;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.opensearch.BulkWriter;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * {@code links --update} (T15 step 4): recomputes {@code github_url} for every already-written
 * {@code log2code-catalog} document (streamed via {@link DocumentReader}) and writes back only the
 * ones whose {@code github_url} actually changed - a full re-index per document (same {@code _id},
 * so it replaces the existing one), not a partial update, reusing the same {@link BulkWriter} every
 * other catalog write already uses. Free of CLI/picocli concerns (unlike {@link LinksCommand}, its
 * only caller) so it can be exercised directly by tests.
 */
final class LinksUpdateRunner {

    private static final int PAGE_SIZE = 500;

    private LinksUpdateRunner() {
    }

    /** {@code total} catalog entries seen, {@code changed} rewritten, {@code linked} now have a URL. */
    record Result(long total, long changed, long linked) {
    }

    static Result run(OpenSearchClient client, IndexNames indexNames, GithubLinker linker) throws IOException {
        long total = 0;
        long changed = 0;
        long linked = 0;

        DocumentReader reader = new DocumentReader(client);
        try (BulkWriter<CatalogEntry> writer = new BulkWriter<>(client, indexNames.catalog(), CatalogEntry::statementId);
             Stream<CatalogEntry> stream = reader.streamAll(indexNames.catalog(), null, CatalogEntry.class, PAGE_SIZE)) {
            Iterator<CatalogEntry> iterator = stream.iterator();
            while (iterator.hasNext()) {
                CatalogEntry entry = iterator.next();
                total++;
                String newUrl = linker.link(entry.codeUnit(), entry.filePath(), entry.line(), entry.endLine());
                if (!Objects.equals(newUrl, entry.githubUrl())) {
                    writer.add(entry.withGithubUrl(newUrl));
                    changed++;
                }
                if (newUrl != null) {
                    linked++;
                }
            }
            writer.flush();
            BulkWriter.Report report = writer.report();
            if (report.failed() > 0) {
                throw new IOException("links --update: bulk write failed for " + report.failed()
                    + " document(s): " + report.errors());
            }
        }
        return new Result(total, changed, linked);
    }
}
