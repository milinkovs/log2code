package org.log2code.analyzer.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.log2code.analyzer.CliUserException;

/** Reads state from a local git repository (project.path from {@code config/analyzer.yml}) via the {@code git} CLI. */
public final class GitRepo {

    private static final int GIT_TIMEOUT_SECONDS = 30;

    private final Path repoDir;

    public GitRepo(Path repoDir) {
        this.repoDir = repoDir;
    }

    /** The full SHA of {@code HEAD}. */
    public String headCommit() {
        return run("rev-parse", "HEAD").trim();
    }

    /** The {@code origin} remote, normalized to {@code https://...} without a trailing {@code .git}. */
    public String remoteUrl() {
        String raw = run("remote", "get-url", "origin").trim();
        return normalizeRemoteUrl(raw);
    }

    /** The {@code .java} files with uncommitted changes (modified, added, deleted, renamed or untracked). */
    public List<String> dirtyJavaFiles() {
        String porcelain = run("status", "--porcelain");
        List<String> javaFiles = new ArrayList<>();
        for (String line : porcelain.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            // Porcelain v1: "XY PATH" or "XY ORIG -> PATH" for renames/copies (XY is always 2 chars).
            String path = line.length() > 3 ? line.substring(3) : "";
            int arrow = path.indexOf(" -> ");
            String effectivePath = arrow >= 0 ? path.substring(arrow + 4) : path;
            if (effectivePath.endsWith(".java")) {
                javaFiles.add(effectivePath);
            }
        }
        return javaFiles;
    }

    static String normalizeRemoteUrl(String raw) {
        String url = raw;
        // git@host:owner/repo(.git) -> https://host/owner/repo
        if (url.startsWith("git@")) {
            String rest = url.substring("git@".length());
            int colon = rest.indexOf(':');
            if (colon >= 0) {
                url = "https://" + rest.substring(0, colon) + "/" + rest.substring(colon + 1);
            }
        } else if (url.startsWith("ssh://git@")) {
            url = "https://" + url.substring("ssh://git@".length());
        }
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - ".git".length());
        }
        return url;
    }

    private String run(String... gitArgs) {
        List<String> command = new ArrayList<>();
        command.add("git");
        for (String arg : gitArgs) {
            command.add(arg);
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(repoDir.toFile());
        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TimeoutException("git " + String.join(" ", gitArgs) + " timed out after " + GIT_TIMEOUT_SECONDS + "s");
            }
            if (process.exitValue() != 0) {
                throw new CliUserException("git " + String.join(" ", gitArgs) + " failed in " + repoDir + ": " + stderr.trim());
            }
            return stdout;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to run git " + String.join(" ", gitArgs) + " in " + repoDir, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliUserException("interrupted while running git " + String.join(" ", gitArgs) + " in " + repoDir);
        } catch (TimeoutException e) {
            throw new CliUserException(e.getMessage());
        }
    }
}
