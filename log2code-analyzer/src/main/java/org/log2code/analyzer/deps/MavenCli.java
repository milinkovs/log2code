package org.log2code.analyzer.deps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.log2code.analyzer.CliUserException;

/**
 * Runs the PetClinic project's {@code ./mvnw} wrapper (T12 steps 1 and 3), the same way {@code GitRepo}
 * (T07) runs {@code git}: a plain {@link ProcessBuilder}, working directory set to the PetClinic checkout.
 * On Windows, {@code mvnw} is a bash script, so {@code mvnw.cmd} is used instead (0.13: Windows workaround);
 * it is resolved to an absolute path because {@code CreateProcess} does not search the working directory
 * for a bare relative file name the way a shell does.
 */
public final class MavenCli {

    private static final int LIST_TIMEOUT_SECONDS = 300;
    private static final int GET_TIMEOUT_SECONDS = 180;

    private final Path projectRoot;
    private final String wrapper;

    public MavenCli(Path projectRoot) {
        this.projectRoot = projectRoot;
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        this.wrapper = projectRoot.resolve(windows ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
    }

    /** {@code mvn dependency:list -DincludeScope=runtime} for one module (T12 step 1), parsed via {@link DependencyListParser}. */
    public List<Artifact> listRuntimeDependencies(String module, Path outputFile) {
        run(LIST_TIMEOUT_SECONDS, "-q", "-pl", module, "dependency:list",
            "-DincludeScope=runtime", "-DoutputAbsoluteArtifactFilename=true",
            "-DoutputFile=" + outputFile.toAbsolutePath(), "-DappendOutput=false");
        try {
            return DependencyListParser.parse(Files.readString(outputFile));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + outputFile, e);
        }
    }

    /** {@code mvn dependency:get ...:sources} (T12 step 3). Returns {@code false} (not thrown) if no sources jar exists. */
    public boolean fetchSources(String gav) {
        try {
            run(GET_TIMEOUT_SECONDS, "-q", "dependency:get", "-Dartifact=" + gav + ":jar:sources", "-Dtransitive=false");
            return true;
        } catch (CliUserException e) {
            return false;
        }
    }

    private void run(int timeoutSeconds, String... mvnArgs) {
        List<String> command = new ArrayList<>();
        command.add(wrapper);
        command.addAll(List.of(mvnArgs));
        ProcessBuilder builder = new ProcessBuilder(command).directory(projectRoot.toFile());
        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CliUserException(wrapper + " " + String.join(" ", mvnArgs) + " timed out after " + timeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                throw new CliUserException(wrapper + " " + String.join(" ", mvnArgs) + " failed in " + projectRoot
                    + ": " + lastLines(stdout + stderr, 20));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to run " + wrapper + " " + String.join(" ", mvnArgs) + " in " + projectRoot, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliUserException("interrupted while running " + wrapper + " " + String.join(" ", mvnArgs) + " in " + projectRoot);
        }
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - n);
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }
}
