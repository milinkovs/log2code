package org.log2code.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    void helpListsTheAblationTuningAndValidationCommands() {
        Outcome outcome = execute("--help");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.out()).contains("run", "ablate", "tune", "validate");
    }

    @Test
    void theNewCommandsDescribeTheirOptions() {
        assertThat(execute("ablate", "--help").out()).contains("--dataset", "--out-dir");
        assertThat(execute("tune", "--help").out()).contains("--dataset", "--seed", "--max-combos", "--min-high-precision");
        assertThat(execute("validate", "--help").out()).contains("--dataset", "--baseline", "--proposal", "--min-high-precision");
    }

    @Test
    void aMissingDatasetFolderIsAUserError() {
        for (String command : new String[] {"ablate", "tune", "validate"}) {
            Outcome outcome = execute(command, "--dataset", "no-such-dataset-folder");

            assertThat(outcome.exitCode()).as(command).isEqualTo(1);
            assertThat(outcome.err()).as(command).contains("dataset folder not found");
        }
    }

    @Test
    void ablateAndTuneRefuseATestDataset(@TempDir Path dir) throws IOException {
        Path dataset = datasetFolder(dir, "test-98");

        for (String command : new String[] {"ablate", "tune"}) {
            Outcome outcome = execute(command, "--dataset", dataset.toString());

            assertThat(outcome.exitCode()).as(command).isEqualTo(1);
            assertThat(outcome.err()).as(command).contains("test-98", "exactly once");
        }
    }

    @Test
    void validateRefusesToRunTwiceOnTheSameDataset(@TempDir Path dir) throws IOException {
        Path dataset = datasetFolder(dir, "test-97");
        Path out = dir.resolve("eval");
        Files.createDirectories(out.resolve("test-97"));
        Files.writeString(out.resolve("test-97/validation.json"), "{}");

        Outcome outcome = execute("validate", "--dataset", dataset.toString(), "--out-dir", out.toString());

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.err()).contains("already validated", "exactly once");
    }

    @Test
    void validateNeedsTheFilesTuneLeaves(@TempDir Path dir) throws IOException {
        Path dataset = datasetFolder(dir, "test-96");

        Outcome outcome = execute("validate", "--dataset", dataset.toString(), "--out-dir", dir.resolve("empty").toString());

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.err()).contains("file not found").contains("tune");
    }

    /** A dataset folder holding only a manifest: enough for the commands that stop before reading any log. */
    private static Path datasetFolder(Path root, String datasetId) throws IOException {
        Path folder = Files.createDirectories(root.resolve(datasetId));
        Files.writeString(folder.resolve("manifest.yml"), """
            dataset_id: %s
            description: "test"
            created_at: 2026-10-08T17:57:45+02:00
            oracle: true
            log_format: spring-boot-default
            code:
              name: petclinic
              version: abc
            files: []
            notes: ""
            """.formatted(datasetId));
        return folder;
    }

    @Test
    void anUnreachableOpenSearchIsAnInternalError() {
        // nothing listens on port 1: the connection fails, which is not a user mistake
        Outcome outcome = execute("--opensearch-url", "http://localhost:1", "run", "--dataset", "x");

        assertThat(outcome.exitCode()).isEqualTo(2);
        assertThat(outcome.err()).contains("internal error");
    }
}
