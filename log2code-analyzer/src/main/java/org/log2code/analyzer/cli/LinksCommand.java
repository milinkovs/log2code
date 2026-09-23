package org.log2code.analyzer.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import org.log2code.analyzer.config.AnalyzerConfig;
import org.log2code.analyzer.config.AnalyzerConfigLoader;
import org.log2code.analyzer.config.CodeUnitsConfigLoader;
import org.log2code.core.github.CodeUnitsConfig;
import org.log2code.core.github.GithubLinker;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * GitHub links for already-written catalog data (T15 step 4): {@code --update} recomputes
 * {@code github_url} for every {@code log2code-catalog} document from {@code config/code-units.yml}
 * and writes back the ones that changed; {@code --verify [--sample N]} (AC3) checks a random sample
 * of linked entries with a real HTTP request and reports the success rate, with each failure
 * explained (status code or error).
 */
@Command(name = "links", description = "GitHub links for already-written catalog data: recompute (--update) or spot-check (--verify).")
public final class LinksCommand implements Callable<Integer> {

    private static final Path CODE_UNITS_CONFIG_FILE = Path.of("config/code-units.yml");
    private static final int DEFAULT_SAMPLE_SIZE = 20;

    @ParentCommand
    private AnalyzerCli parent;

    @ArgGroup(multiplicity = "1")
    private Action action;

    // Elements of an ArgGroup must not be marked required=true themselves; the group's own
    // multiplicity ("exactly one of --update/--verify") already enforces that (same pattern as IndicesCommand).
    private static final class Action {
        @Option(names = "--update", description = "Recompute github_url for every log2code-catalog document and write back the ones that changed.")
        boolean update;

        @Option(names = "--verify", description = "Sample catalog entries that have a github_url and check each with a real HTTP request.")
        boolean verify;
    }

    @Option(names = "--sample", defaultValue = "" + DEFAULT_SAMPLE_SIZE,
        description = "Sample size for --verify (default: ${DEFAULT-VALUE}).")
    private int sample;

    @Override
    public Integer call() throws IOException {
        AnalyzerConfig config = AnalyzerConfigLoader.load(parent.configPath());
        String url = parent.openSearchUrl() != null ? parent.openSearchUrl() : config.opensearch().url();

        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(url));
        try {
            if (action.update) {
                return runUpdate(client);
            }
            return runVerify(client);
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private Integer runUpdate(OpenSearchClient client) throws IOException {
        CodeUnitsConfig codeUnitsConfig = CodeUnitsConfigLoader.load(CODE_UNITS_CONFIG_FILE);
        GithubLinker linker = new GithubLinker(codeUnitsConfig, null);
        LinksUpdateRunner.Result result = LinksUpdateRunner.run(client, new IndexNames(), linker);
        System.out.printf("checked %d catalog entries, rewrote %d, %d now have a github_url%n",
            result.total(), result.changed(), result.linked());
        return 0;
    }

    private Integer runVerify(OpenSearchClient client) throws IOException {
        LinksVerifyRunner.Result result = LinksVerifyRunner.run(client, new IndexNames(), sample, LinksVerifyRunner.realHttpChecker());
        System.out.printf(Locale.ROOT, "verified %d/%d link(s) (%.1f%%)%n", result.ok(), result.total(), result.okRate() * 100);
        for (LinksVerifyRunner.CheckResult check : result.checks()) {
            if (!check.ok()) {
                String reason = check.error() != null ? "error: " + check.error() : "HTTP " + check.status();
                System.err.println("  " + check.statementId() + "  " + check.url() + "  -> " + reason);
            }
        }
        return 0;
    }
}
