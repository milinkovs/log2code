package org.log2code.analyzer.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** GitRepo against a real, throwaway repository under {@code git init} (0.12 test spec: clean, dirty, dirty .java). */
class GitRepoTest {

    @TempDir
    Path repoDir;

    private GitRepo repo;

    @BeforeEach
    void initRepo() throws IOException, InterruptedException {
        git(repoDir, "init", "-b", "main");
        git(repoDir, "config", "user.name", "log2code-test");
        git(repoDir, "config", "user.email", "log2code-test@example.com");
        repo = new GitRepo(repoDir);
    }

    @Test
    void headCommitMatchesGitRevParse() throws IOException, InterruptedException {
        writeAndCommit("App.java", "class App {}", "initial commit");

        String expected = git(repoDir, "rev-parse", "HEAD").trim();
        assertThat(repo.headCommit()).isEqualTo(expected).hasSize(40);
    }

    @Test
    void cleanRepoHasNoDirtyJavaFiles() throws IOException, InterruptedException {
        writeAndCommit("App.java", "class App {}", "initial commit");

        assertThat(repo.dirtyJavaFiles()).isEmpty();
    }

    @Test
    void dirtyNonJavaFileIsNotReportedAsDirtyJava() throws IOException, InterruptedException {
        writeAndCommit("README.md", "hello", "initial commit");
        Files.writeString(repoDir.resolve("README.md"), "changed", StandardCharsets.UTF_8);

        assertThat(repo.dirtyJavaFiles()).isEmpty();
    }

    @Test
    void detectsModifiedTrackedJavaFile() throws IOException, InterruptedException {
        writeAndCommit("Existing.java", "class Existing {}", "initial commit");
        Files.writeString(repoDir.resolve("Existing.java"), "class Existing { void m() {} }", StandardCharsets.UTF_8);

        assertThat(repo.dirtyJavaFiles()).containsExactly("Existing.java");
    }

    @Test
    void detectsUntrackedNewJavaFile() throws IOException, InterruptedException {
        writeAndCommit("Existing.java", "class Existing {}", "initial commit");
        Files.writeString(repoDir.resolve("New.java"), "class New {}", StandardCharsets.UTF_8);

        assertThat(repo.dirtyJavaFiles()).containsExactly("New.java");
    }

    @Test
    void detectsDeletedTrackedJavaFile() throws IOException, InterruptedException {
        writeAndCommit("Existing.java", "class Existing {}", "initial commit");
        git(repoDir, "rm", "Existing.java");

        assertThat(repo.dirtyJavaFiles()).containsExactly("Existing.java");
    }

    @Test
    void nonJavaAndJavaDirtyFilesCoexist() throws IOException, InterruptedException {
        writeAndCommit("Existing.java", "class Existing {}", "initial commit");
        Files.writeString(repoDir.resolve("Existing.java"), "class Existing { void m() {} }", StandardCharsets.UTF_8);
        Files.writeString(repoDir.resolve("notes.txt"), "wip", StandardCharsets.UTF_8);

        assertThat(repo.dirtyJavaFiles()).containsExactly("Existing.java");
    }

    @Test
    void remoteUrlNormalizesHttpsWithDotGitSuffix() throws IOException, InterruptedException {
        git(repoDir, "remote", "add", "origin", "https://github.com/spring-petclinic/spring-petclinic-microservices.git");

        assertThat(repo.remoteUrl()).isEqualTo("https://github.com/spring-petclinic/spring-petclinic-microservices");
    }

    @Test
    void remoteUrlNormalizesSshForm() throws IOException, InterruptedException {
        git(repoDir, "remote", "add", "origin", "git@github.com:spring-petclinic/spring-petclinic-microservices.git");

        assertThat(repo.remoteUrl()).isEqualTo("https://github.com/spring-petclinic/spring-petclinic-microservices");
    }

    @Test
    void remoteUrlNormalizeHelperHandlesSshUrlForm() {
        assertThat(GitRepo.normalizeRemoteUrl("ssh://git@github.com/foo/bar.git")).isEqualTo("https://github.com/foo/bar");
    }

    private void writeAndCommit(String fileName, String content, String message) throws IOException, InterruptedException {
        Files.writeString(repoDir.resolve(fileName), content, StandardCharsets.UTF_8);
        git(repoDir, "add", fileName);
        git(repoDir, "commit", "-m", message);
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + stderr);
        }
        return stdout;
    }
}
