package org.log2code.analyzer.cli;

import java.util.concurrent.Callable;
import org.log2code.analyzer.AnalyzerCli;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

/** Dependency resolution ({@code resolve}, T12) and source analysis ({@code analyze}, T14). */
@Command(name = "deps", description = "Dependency resolution and source analysis.",
    subcommands = {DepsResolveCommand.class, DepsAnalyzeCommand.class, HelpCommand.class})
public final class DepsCommand implements Callable<Integer> {

    @ParentCommand
    private AnalyzerCli parent;

    AnalyzerCli parent() {
        return parent;
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }
}
