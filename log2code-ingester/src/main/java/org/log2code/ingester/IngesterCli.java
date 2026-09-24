package org.log2code.ingester;

import java.util.concurrent.Callable;
import org.log2code.ingester.catalog.CatalogLoadException;
import org.log2code.ingester.cli.ExplainCommand;
import org.log2code.ingester.cli.FollowCommand;
import org.log2code.ingester.cli.IngestCommand;
import org.log2code.ingester.cli.StatsCommand;
import org.log2code.ingester.manifest.ManifestException;
import org.log2code.ingester.match.MatchingConfigException;
import org.log2code.ingester.parse.LogFormatException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

/**
 * log2code ingester CLI (T21): loads a dataset (T16), assembles its log files into events (T17/T18),
 * matches each one against the in-memory catalog (T19/T20), enriches it and writes it to
 * {@code log2code-logs} - idempotently, since documents are indexed by {@code log_id} (0.8).
 *
 * <p>Exit codes (0.14): {@code 0} success, {@code 1} user/input error, {@code 2} internal error.
 */
@Command(
    name = "ingester",
    mixinStandardHelpOptions = true,
    version = "log2code-ingester",
    description = "Ingests a dataset's log files, matches them against the catalog and writes enriched events to log2code-logs.",
    subcommands = {IngestCommand.class, StatsCommand.class, ExplainCommand.class, FollowCommand.class, CommandLine.HelpCommand.class}
)
public final class IngesterCli implements Callable<Integer> {

    @Option(names = "--opensearch-url", defaultValue = "http://localhost:9200", scope = ScopeType.INHERIT,
        description = "OpenSearch base URL (default: ${DEFAULT-VALUE}).")
    private String openSearchUrl;

    public String openSearchUrl() {
        return openSearchUrl;
    }

    @Override
    public Integer call() {
        // No subcommand given: behave like --help.
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new IngesterCli())
            .setExecutionExceptionHandler(new ExitCodeExecutionExceptionHandler())
            .setParameterExceptionHandler(new ExitCodeParameterExceptionHandler());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    /**
     * User/input errors (bad dataset, bad config, bad {@code --at}) exit 1; anything else exits 2.
     * Unlike the analyzer's single {@code CliUserException}, the ingester's user-error conditions were
     * each already given their own exception type by the task that introduced them (T17's
     * {@link LogFormatException}, T19's {@link CatalogLoadException}, T20's {@link MatchingConfigException})
     * - this checks all of them plus T21's own {@link ManifestException}/{@link IngesterUserException}
     * rather than retrofitting those into a shared base type.
     */
    private static final class ExitCodeExecutionExceptionHandler implements IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
            if (isUserError(ex)) {
                cmd.getErr().println("error: " + ex.getMessage());
                return 1;
            }
            cmd.getErr().println("internal error: " + ex);
            ex.printStackTrace(cmd.getErr());
            return 2;
        }

        private static boolean isUserError(Exception ex) {
            return ex instanceof IngesterUserException
                || ex instanceof ManifestException
                || ex instanceof LogFormatException
                || ex instanceof MatchingConfigException
                || ex instanceof CatalogLoadException;
        }
    }

    /** Bad CLI usage (unknown option/subcommand, missing argument) is a user error (0.14), not exit code 2. */
    private static final class ExitCodeParameterExceptionHandler implements IParameterExceptionHandler {
        @Override
        public int handleParseException(ParameterException ex, String[] args) {
            CommandLine cmd = ex.getCommandLine();
            cmd.getErr().println(ex.getMessage());
            if (!CommandLine.UnmatchedArgumentException.printSuggestions(ex, cmd.getErr())) {
                cmd.usage(cmd.getErr());
            }
            return 1;
        }
    }
}
