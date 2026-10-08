package org.log2code.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class EvalCliTest {

    private record Outcome(int exitCode, String out, String err) {
    }

    private static Outcome execute(String... args) {
        CommandLine cli = EvalCli.commandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        int exitCode = cli.execute(args);
        return new Outcome(exitCode, out.toString(), err.toString());
    }

    @Test
    void helpListsTheRunCommand() {
        Outcome outcome = execute("--help");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.out()).contains("run").contains("--opensearch-url");
    }

    @Test
    void runHelpDescribesItsOptions() {
        Outcome outcome = execute("run", "--help");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.out()).contains("--dataset").contains("--out-dir").contains("--seed").contains("--error-samples");
    }

    @Test
    void aMissingRequiredOptionIsAUserError() {
        Outcome outcome = execute("run");

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.err()).contains("--dataset");
    }

    @Test
    void anUnknownCommandIsAUserError() {
        assertThat(execute("frobnicate").exitCode()).isEqualTo(1);
    }

    @Test
    void anUnreachableOpenSearchIsAnInternalError() {
        // nothing listens on port 1: the connection fails, which is not a user mistake
        Outcome outcome = execute("--opensearch-url", "http://localhost:1", "run", "--dataset", "x");

        assertThat(outcome.exitCode()).isEqualTo(2);
        assertThat(outcome.err()).contains("internal error");
    }
}
