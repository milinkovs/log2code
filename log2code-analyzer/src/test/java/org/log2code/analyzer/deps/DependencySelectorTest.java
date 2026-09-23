package org.log2code.analyzer.deps;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.analyzer.deps.DependencySelector.ModuleSelection;
import org.log2code.analyzer.deps.DependencySelector.SelectedArtifact;

/**
 * T12 step 2 (auto-select) with small synthetic jars (real {@link java.util.jar.JarFile}s, built in a temp
 * dir, so {@link JarClassIndex} exercises real jar-entry reading rather than a mock).
 */
class DependencySelectorTest {

    @Test
    void selectsAnArtifactWhoseJarContainsAMatchingClass(@TempDir Path dir) throws IOException {
        Artifact artifact = artifact(dir, "com.example", "foo-lib", "1.0", "com.example.Foo", "com.example.Bar");
        Map<String, Integer> loggers = Map.of("com.example.Foo", 5);

        ModuleSelection selection = DependencySelector.select(
            new ModuleDeps("m", List.of(artifact)), loggers, Set.of(), List.of(), List.of());

        SelectedArtifact result = selection.artifacts().get(0);
        assertThat(result.selected()).isTrue();
        assertThat(result.reason()).isEqualTo("seen-in-logs");
        assertThat(result.loggerHits()).isEqualTo(1);
        assertThat(selection.mappedLoggers()).containsExactly("com.example.Foo");
        assertThat(selection.unmappedLoggers()).isEmpty();
    }

    @Test
    void loggerHitsCountsDistinctMatchedLoggersNotOccurrences(@TempDir Path dir) throws IOException {
        Artifact artifact = artifact(dir, "com.example", "foo-lib", "1.0", "com.example.Foo", "com.example.Bar");
        Map<String, Integer> loggers = new LinkedHashMap<>();
        loggers.put("com.example.Foo", 10);
        loggers.put("com.example.Bar", 3);

        ModuleSelection selection = DependencySelector.select(
            new ModuleDeps("m", List.of(artifact)), loggers, Set.of(), List.of(), List.of());

        assertThat(selection.artifacts().get(0).loggerHits()).isEqualTo(2);
    }

    @Test
    void loggersMatchingAProjectClassAreSkippedAndNeverCountTowardsAnArtifact(@TempDir Path dir) throws IOException {
        Artifact artifact = artifact(dir, "com.example", "foo-lib", "1.0", "com.example.Foo");
        Map<String, Integer> loggers = Map.of("com.example.Foo", 1, "com.example.MyProjectClass", 1);

        ModuleSelection selection = DependencySelector.select(
            new ModuleDeps("m", List.of(artifact)), loggers, Set.of("com.example.MyProjectClass"), List.of(), List.of());

        assertThat(selection.artifacts().get(0).loggerHits()).isEqualTo(1);
        assertThat(selection.mappedLoggers()).containsExactlyInAnyOrder("com.example.Foo", "com.example.MyProjectClass");
    }

    @Test
    void configIncludeSelectsAnArtifactWithNoLoggerHits(@TempDir Path dir) throws IOException {
        Artifact artifact = artifact(dir, "org.webjars", "bootstrap", "5.0", "org.webjars.Marker");

        ModuleSelection selection = DependencySelector.select(new ModuleDeps("m", List.of(artifact)),
            Map.of(), Set.of(), List.of("org.webjars:*"), List.of());

        SelectedArtifact result = selection.artifacts().get(0);
        assertThat(result.selected()).isTrue();
        assertThat(result.reason()).isEqualTo("config-include");
        assertThat(result.loggerHits()).isZero();
    }

    @Test
    void configExcludeWinsOverSeenInLogsAndInclude(@TempDir Path dir) throws IOException {
        Artifact artifact = artifact(dir, "org.webjars", "bootstrap", "5.0", "org.webjars.Marker");
        Map<String, Integer> loggers = Map.of("org.webjars.Marker", 1);

        ModuleSelection selection = DependencySelector.select(new ModuleDeps("m", List.of(artifact)),
            loggers, Set.of(), List.of("org.webjars:*"), List.of("org.webjars:*"));

        SelectedArtifact result = selection.artifacts().get(0);
        assertThat(result.selected()).isFalse();
        assertThat(result.reason()).isEqualTo("config-exclude");
    }

    @Test
    void unselectedArtifactWithNoRuleHasNoReason(@TempDir Path dir) throws IOException {
        Artifact artifact = artifact(dir, "com.example", "unused-lib", "1.0", "com.example.Unused");

        ModuleSelection selection = DependencySelector.select(
            new ModuleDeps("m", List.of(artifact)), Map.of(), Set.of(), List.of(), List.of());

        assertThat(selection.artifacts().get(0).selected()).isFalse();
        assertThat(selection.artifacts().get(0).reason()).isNull();
    }

    @Test
    void nonClassShapedLoggersAreExcludedNotUnmapped(@TempDir Path dir) throws IOException {
        Artifact artifact = artifact(dir, "com.example", "foo-lib", "1.0", "com.example.Foo");
        Map<String, Integer> loggers = Map.of(
            "com.example.Foo", 1,
            "o.a.c.c.C.[Tomcat].[localhost].[/]", 3,
            "org.hibernate.orm.core", 2);

        ModuleSelection selection = DependencySelector.select(
            new ModuleDeps("m", List.of(artifact)), loggers, Set.of(), List.of(), List.of());

        assertThat(selection.excludedLoggers()).containsExactly("o.a.c.c.C.[Tomcat].[localhost].[/]");
        assertThat(selection.unmappedLoggers()).containsExactly("org.hibernate.orm.core");
        assertThat(selection.mappedLoggers()).containsExactly("com.example.Foo");
    }

    private static Artifact artifact(Path dir, String groupId, String artifactId, String version, String... classFqns)
        throws IOException {
        Path jar = dir.resolve(artifactId + "-" + version + ".jar");
        writeJar(jar, classFqns);
        return new Artifact(groupId, artifactId, version, "jar", "compile", jar);
    }

    private static void writeJar(Path jar, String... classFqns) throws IOException {
        try (OutputStream fileOut = java.nio.file.Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            for (String fqn : classFqns) {
                jarOut.putNextEntry(new JarEntry(fqn.replace('.', '/') + ".class"));
                jarOut.closeEntry();
            }
        }
    }
}
