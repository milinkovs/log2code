package org.log2code.analyzer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.analyzer.CliUserException;

class AnalyzerConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsAllSectionsWithKebabCaseKeys() throws IOException {
        Path file = writeConfig("""
            project:
              name: spring-petclinic-microservices
              path: ../spring-petclinic-microservices
              include-modules: ["spring-petclinic-customers-service"]
              exclude-modules: ["spring-petclinic-admin-server"]
            context:
              snippet-lines: 7
              max-preceding-statements: 12
            dependencies:
              include: ["org.springframework:*"]
              exclude: ["org.webjars:*"]
              auto-select-from-logs: [data/logs, fixtures/logs]
            opensearch:
              url: http://localhost:9200
            """);

        AnalyzerConfig config = AnalyzerConfigLoader.load(file);

        assertThat(config.project().name()).isEqualTo("spring-petclinic-microservices");
        assertThat(config.project().path()).isEqualTo("../spring-petclinic-microservices");
        assertThat(config.project().includeModules()).containsExactly("spring-petclinic-customers-service");
        assertThat(config.project().excludeModules()).containsExactly("spring-petclinic-admin-server");
        assertThat(config.context().snippetLines()).isEqualTo(7);
        assertThat(config.context().maxPrecedingStatements()).isEqualTo(12);
        assertThat(config.dependencies().include()).containsExactly("org.springframework:*");
        assertThat(config.dependencies().exclude()).containsExactly("org.webjars:*");
        assertThat(config.dependencies().autoSelectFromLogs()).containsExactly("data/logs", "fixtures/logs");
        assertThat(config.opensearch().url()).isEqualTo("http://localhost:9200");
    }

    @Test
    void appliesDefaultsForOmittedSections() throws IOException {
        Path file = writeConfig("""
            project:
              name: spring-petclinic-microservices
              path: ../spring-petclinic-microservices
            """);

        AnalyzerConfig config = AnalyzerConfigLoader.load(file);

        assertThat(config.project().includeModules()).isEmpty();
        assertThat(config.context().snippetLines()).isEqualTo(5);
        assertThat(config.context().maxPrecedingStatements()).isEqualTo(10);
        assertThat(config.dependencies().include()).isEmpty();
        assertThat(config.opensearch().url()).isEqualTo("http://localhost:9200");
    }

    @Test
    void missingFileIsAUserError() {
        assertThatThrownBy(() -> AnalyzerConfigLoader.load(dir.resolve("does-not-exist.yml")))
            .isInstanceOf(CliUserException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void missingProjectSectionIsAUserError() throws IOException {
        Path file = writeConfig("opensearch:\n  url: http://localhost:9200\n");

        assertThatThrownBy(() -> AnalyzerConfigLoader.load(file))
            .isInstanceOf(CliUserException.class);
    }

    @Test
    void theRealProjectConfigFileParses() {
        // scripts/analyzer.sh always runs from the log2code/ repo root; from the module dir (Surefire's
        // working directory) that same file is one level up.
        Path realConfig = Path.of("../config/analyzer.yml");
        assertThat(realConfig).exists();

        AnalyzerConfig config = AnalyzerConfigLoader.load(realConfig);

        assertThat(config.project().name()).isEqualTo("spring-petclinic-microservices");
        assertThat(config.project().path()).isEqualTo("../spring-petclinic-microservices");
        assertThat(config.opensearch().url()).isEqualTo("http://localhost:9200");
    }

    private Path writeConfig(String yaml) throws IOException {
        Path file = dir.resolve("analyzer.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }
}
