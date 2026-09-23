package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.TypeInfo;

/**
 * Against a synthetic {@code -sources.jar} (T14): cross-file inherited-logger resolution still works
 * with no symbol solver (ADR-008), and a {@link SourceFile} is only built for a file with at least one
 * detected log statement (T14 step 6) - unlike {@code ProjectCatalogBuilder}, which builds one for
 * every project file regardless of whether it logs anything.
 */
class DependencyCatalogBuilderTest {

    private static final String ANALYZER_VERSION = "test-analyzer";
    private static final Instant ANALYZED_AT = Instant.parse("2026-09-23T10:00:00Z");

    @Test
    void sourceFileOnlyForFilesWithLogStatementsButTypeInfoForAll(@TempDir Path dir) throws IOException {
        Path jar = writeSourcesJar(dir);
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "com.example:mini-lib", "1.0.0");

        DependencyCatalogBuilder.Result result =
            DependencyCatalogBuilder.build(jar, codeUnit, 3, 10, ANALYZER_VERSION, ANALYZED_AT);

        assertThat(result.catalog()).hasSize(1);
        CatalogEntry entry = result.catalog().get(0);
        assertThat(entry.template()).isEqualTo("Handling {}");
        assertThat(entry.classFqn()).isEqualTo("com.example.lib.ServiceA");
        assertThat(entry.detection()).isEqualTo("inherited");
        assertThat(entry.module()).isEqualTo("com.example:mini-lib");
        assertThat(entry.service()).isNull();
        assertThat(entry.methodId()).isNull();
        assertThat(entry.filePath()).isEqualTo("com/example/lib/ServiceA.java");
        assertThat(entry.codeUnit()).isEqualTo(codeUnit);

        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources()).extracting(SourceFile::filePath).containsExactly("com/example/lib/ServiceA.java");

        assertThat(result.types()).hasSize(3);
        assertThat(result.types()).extracting(TypeInfo::classFqn).containsExactlyInAnyOrder(
            "com.example.lib.LoggingBase", "com.example.lib.ServiceA", "com.example.lib.NoLogging");

        assertThat(result.parseFailures()).isEmpty();
        assertThat(result.stats().get("file_count")).isEqualTo(3);
        assertThat(result.stats().get("source_file_count")).isEqualTo(1);
        assertThat(result.stats().get("type_count")).isEqualTo(3);
        assertThat(result.stats().get("statement_count")).isEqualTo(1);
        assertThat(result.stats().get("parse_error_count")).isEqualTo(0);
        assertThat(result.stats().get("calls_by_logging_api")).isEqualTo(Map.of("slf4j", 1L));
    }

    @Test
    void secondBuildIsIdempotentLikeTheProjectBuilder(@TempDir Path dir) throws IOException {
        Path jar = writeSourcesJar(dir);
        CodeUnit codeUnit = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "com.example:mini-lib", "1.0.0");

        String first = DependencyCatalogBuilder.build(jar, codeUnit, 3, 10, ANALYZER_VERSION, ANALYZED_AT)
            .catalog().get(0).statementId();
        String second = DependencyCatalogBuilder.build(jar, codeUnit, 3, 10, ANALYZER_VERSION, ANALYZED_AT)
            .catalog().get(0).statementId();

        assertThat(first).isEqualTo(second);
    }

    private static Path writeSourcesJar(Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("com/example/lib/LoggingBase.java", """
            package com.example.lib;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public abstract class LoggingBase {
                protected static final Logger log = LoggerFactory.getLogger(LoggingBase.class);
            }
            """);
        entries.put("com/example/lib/ServiceA.java", """
            package com.example.lib;

            public class ServiceA extends LoggingBase {
                public void run() {
                    log.info("Handling {}", "x");
                }
            }
            """);
        entries.put("com/example/lib/NoLogging.java", """
            package com.example.lib;

            public class NoLogging {
                public void doNothing() {
                }
            }
            """);

        Path jar = dir.resolve("mini-lib-sources.jar");
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
