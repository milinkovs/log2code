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
import org.log2code.core.github.CodeUnitsConfig;

class CodeUnitsConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsProjectAndDependencyMappings() throws IOException {
        Path file = writeConfig("""
            project:
              spring-petclinic-microservices:
                repo: https://github.com/spring-petclinic/spring-petclinic-microservices
                ref: "{version}"
                path: "{file_path}"
            dependencies:
              - match: "org.springframework:*"
                repo: https://github.com/spring-projects/spring-framework
                ref: "v{version}"
                path: "{artifactId}/src/main/java/{file_path}"
            """);

        CodeUnitsConfig config = CodeUnitsConfigLoader.load(file);

        assertThat(config.project()).containsKey("spring-petclinic-microservices");
        CodeUnitsConfig.ProjectMapping projectMapping = config.project().get("spring-petclinic-microservices");
        assertThat(projectMapping.repo()).isEqualTo("https://github.com/spring-petclinic/spring-petclinic-microservices");
        assertThat(projectMapping.ref()).isEqualTo("{version}");
        assertThat(projectMapping.path()).isEqualTo("{file_path}");

        assertThat(config.dependencies()).hasSize(1);
        CodeUnitsConfig.DependencyMapping dependencyMapping = config.dependencies().get(0);
        assertThat(dependencyMapping.match()).isEqualTo("org.springframework:*");
        assertThat(dependencyMapping.repo()).isEqualTo("https://github.com/spring-projects/spring-framework");
    }

    @Test
    void appliesDefaultsForOmittedSections() throws IOException {
        Path file = writeConfig("project: {}\n");

        CodeUnitsConfig config = CodeUnitsConfigLoader.load(file);

        assertThat(config.project()).isEmpty();
        assertThat(config.dependencies()).isEmpty();
    }

    @Test
    void missingFileIsAUserError() {
        assertThatThrownBy(() -> CodeUnitsConfigLoader.load(dir.resolve("does-not-exist.yml")))
            .isInstanceOf(CliUserException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void theRealCodeUnitsConfigFileParses() {
        // scripts/analyzer.sh always runs from the log2code/ repo root; from the module dir (Surefire's
        // working directory) that same file is one level up (same convention as AnalyzerConfigLoaderTest).
        Path realConfig = Path.of("../config/code-units.yml");
        assertThat(realConfig).exists();

        CodeUnitsConfig config = CodeUnitsConfigLoader.load(realConfig);

        assertThat(config.project()).containsKey("spring-petclinic-microservices");
        assertThat(config.dependencies()).isNotEmpty();
        for (CodeUnitsConfig.DependencyMapping mapping : config.dependencies()) {
            assertThat(mapping.match()).isNotBlank();
            assertThat(mapping.repo()).startsWith("https://github.com/");
            assertThat(mapping.ref()).isNotBlank();
            assertThat(mapping.path()).isNotBlank();
        }
    }

    private Path writeConfig(String yaml) throws IOException {
        Path file = dir.resolve("code-units.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }
}
