package org.log2code.analyzer.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import org.log2code.analyzer.CliUserException;
import org.log2code.analyzer.config.AnalyzerConfig;
import org.log2code.analyzer.config.AnalyzerConfigLoader;
import org.log2code.core.opensearch.IndexManager;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** Creates or recreates the 7 OpenSearch indices from 0.7 (delegates to {@link IndexManager} from T05). */
@Command(name = "indices", description = "Create or recreate the log2code-* OpenSearch indices.")
public final class IndicesCommand implements Callable<Integer> {

    private static final String ALL = "all";

    @ParentCommand
    private AnalyzerCli parent;

    @ArgGroup(multiplicity = "1")
    private Action action;

    // Elements of an ArgGroup must not be marked required=true themselves; the group's own
    // multiplicity ("exactly one of --create/--recreate") already enforces that.
    private static final class Action {
        @Option(names = "--create", description = "Create every index that does not already exist.")
        boolean create;

        @Option(names = "--recreate", paramLabel = "<ime|all>",
            description = "Drop and recreate one index (its base name, e.g. log2code-catalog) or 'all'.")
        String recreate;
    }

    @Override
    public Integer call() throws IOException {
        AnalyzerConfig config = AnalyzerConfigLoader.load(parent.configPath());
        String url = parent.openSearchUrl() != null ? parent.openSearchUrl() : config.opensearch().url();

        OpenSearchClient client = OpenSearchClientFactory.create(OpenSearchConfig.of(url));
        try {
            IndexManager manager = new IndexManager(client, new IndexNames());
            if (action.create) {
                manager.ensureAll();
                System.out.println("created missing indices in " + url + ": " + IndexNames.BASE_NAMES);
            } else {
                recreate(manager, action.recreate);
            }
            return 0;
        } finally {
            OpenSearchClientFactory.close(client);
        }
    }

    private void recreate(IndexManager manager, String requested) throws IOException {
        if (ALL.equals(requested)) {
            for (String base : IndexNames.BASE_NAMES) {
                manager.recreate(base);
            }
            System.out.println("recreated all 7 indices: " + IndexNames.BASE_NAMES);
            return;
        }
        if (!IndexNames.BASE_NAMES.contains(requested)) {
            throw new CliUserException("unknown index '" + requested + "': expected 'all' or one of " + IndexNames.BASE_NAMES);
        }
        manager.recreate(requested);
        System.out.println("recreated " + requested);
    }
}
