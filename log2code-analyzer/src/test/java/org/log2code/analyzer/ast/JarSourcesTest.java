package org.log2code.analyzer.ast;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarSourcesTest {

    @Test
    void parsesOnlyJavaEntriesInSortedOrder(@TempDir Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("b/B.java", "package b; public class B { }");
        entries.put("a/A.java", "package a; public class A { }");
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0");
        entries.put("a/notes.txt", "not java");
        Path jar = writeJar(dir.resolve("lib-sources.jar"), entries);

        JarSources.Result result = JarSources.parseAll(jar);

        assertThat(result.failures()).isEmpty();
        assertThat(result.files()).extracting(JarSources.ParsedFile::relativePath)
            .containsExactly("a/A.java", "b/B.java");
        assertThat(result.files().get(0).unit().getPackageDeclaration()).isPresent();
        assertThat(new String(result.files().get(0).bytes(), StandardCharsets.UTF_8)).contains("class A");
    }

    @Test
    void reportsAParseFailureWithoutStoppingTheRest(@TempDir Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Broken.java", "this is not valid java {{{");
        entries.put("Ok.java", "public class Ok { }");
        Path jar = writeJar(dir.resolve("broken-sources.jar"), entries);

        JarSources.Result result = JarSources.parseAll(jar);

        assertThat(result.failures()).extracting(JarSources.ParseFailure::relativePath).containsExactly("Broken.java");
        assertThat(result.files()).extracting(JarSources.ParsedFile::relativePath).containsExactly("Ok.java");
    }

    @Test
    void emptyJarYieldsNoFilesAndNoFailures(@TempDir Path dir) throws IOException {
        Path jar = writeJar(dir.resolve("empty-sources.jar"), Map.of());

        JarSources.Result result = JarSources.parseAll(jar);

        assertThat(result.files()).isEmpty();
        assertThat(result.failures()).isEmpty();
    }

    private static Path writeJar(Path jar, Map<String, String> entries) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jarOut.putNextEntry(new JarEntry(entry.getKey()));
                jarOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        }
        return jar;
    }
}
