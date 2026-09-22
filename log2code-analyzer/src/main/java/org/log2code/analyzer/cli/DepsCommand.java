package org.log2code.analyzer.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

/** Dependency resolution and source analysis: subcommands are added by T12 (resolve/select) and T14 (analyze). */
@Command(name = "deps", description = "Dependency resolution and source analysis (subcommands added by T12/T14).",
    subcommands = {HelpCommand.class})
public final class DepsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("deps: no subcommands yet (T12 adds dependency resolution, T14 adds source analysis).");
        return 0;
    }
}
