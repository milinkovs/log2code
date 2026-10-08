package org.log2code.eval;

import java.util.concurrent.Callable;
import org.log2code.eval.cli.RunCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

/**
 * log2code evaluation CLI (T33): measures how well log events were linked to their log statements,
 * using the oracle ground truth of a dataset and manual labels. T34 adds {@code ablate} and {@code tune}.
 *
 * <p>Exit codes (0.14): {@code 0} success, {@code 1} user/input error, {@code 2} internal error.
 */
@Command(
    name = "eval",
    mixinStandardHelpOptions = true,
    version = "log2code-eval",
    description = "Measures the accuracy of linking log events to log statements.",
    subcommands = {RunCommand.class, CommandLine.HelpCommand.class}
)
public final class EvalCli implements Callable<Integer> {

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
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        return new CommandLine(new EvalCli())
            .setExecutionExceptionHandler(new ExitCodeExecutionExceptionHandler())
            .setParameterExceptionHandler(new ExitCodeParameterExceptionHandler());
    }

    /** User/input errors exit 1; anything else exits 2. */
    private static final class ExitCodeExecutionExceptionHandler implements IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
            if (ex instanceof EvalUserException) {
                cmd.getErr().println("error: " + ex.getMessage());
                return 1;
            }
            cmd.getErr().println("internal error: " + ex);
            ex.printStackTrace(cmd.getErr());
            return 2;
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
