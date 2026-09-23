package org.log2code.analyzer;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.log2code.analyzer.cli.DepsCommand;
import org.log2code.analyzer.cli.IndicesCommand;
import org.log2code.analyzer.cli.LinksCommand;
import org.log2code.analyzer.cli.OutputMode;
import org.log2code.analyzer.cli.OutputModeConverter;
import org.log2code.analyzer.cli.ProjectCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

/**
 * log2code static analyzer CLI (T07): reads {@code config/analyzer.yml}, the local git repository and the
 * project's Maven modules, and (from T08 onward) the log statements themselves.
 *
 * <p>Exit codes (0.14): {@code 0} success, {@code 1} user/input error, {@code 2} internal error.
 */
@Command(
    name = "analyzer",
    mixinStandardHelpOptions = true,
    version = "log2code-analyzer",
    description = "Static analysis of PetClinic and its dependencies: builds the log2code catalog.",
    subcommands = {ProjectCommand.class, DepsCommand.class, IndicesCommand.class, LinksCommand.class, CommandLine.HelpCommand.class}
)
public final class AnalyzerCli implements Callable<Integer> {

    @Option(names = "--config", defaultValue = "config/analyzer.yml", scope = ScopeType.INHERIT,
        description = "Path to the analyzer config file (default: ${DEFAULT-VALUE}).")
    private Path configPath;

    @Option(names = "--opensearch-url", scope = ScopeType.INHERIT,
        description = "Overrides opensearch.url from --config.")
    private String openSearchUrl;

    @Option(names = "--out", defaultValue = "opensearch", converter = OutputModeConverter.class, scope = ScopeType.INHERIT,
        description = "Where the project command writes its results: opensearch|json|both (default: ${DEFAULT-VALUE}).")
    private OutputMode out;

    @Option(names = "--json-dir", defaultValue = "data/work/analyzer", scope = ScopeType.INHERIT,
        description = "Base directory for --out json|both (default: ${DEFAULT-VALUE}).")
    private Path jsonDir;

    public Path configPath() {
        return configPath;
    }

    public String openSearchUrl() {
        return openSearchUrl;
    }

    public OutputMode out() {
        return out;
    }

    public Path jsonDir() {
        return jsonDir;
    }

    @Override
    public Integer call() {
        // No subcommand given: behave like --help.
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new AnalyzerCli())
            .setExecutionExceptionHandler(new ExitCodeExecutionExceptionHandler())
            .setParameterExceptionHandler(new ExitCodeParameterExceptionHandler());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    /** User/input errors (bad config, bad repo state, bad argument) exit 1; anything else exits 2. */
    private static final class ExitCodeExecutionExceptionHandler implements IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
            if (ex instanceof CliUserException) {
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
