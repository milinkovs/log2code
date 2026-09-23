package org.log2code.core.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CodeUnit;

class GithubLinkerTest {

    private static final CodeUnit PROJECT = new CodeUnit(
        CodeUnit.TYPE_PROJECT, "spring-petclinic-microservices", "3858f9c630cf989bb6809a86edf47c2be78dc9f1");

    @Test
    void projectUsesItsOwnMappingRepoAndSingleLineAnchor() {
        CodeUnitsConfig config = new CodeUnitsConfig(
            Map.of("spring-petclinic-microservices", new CodeUnitsConfig.ProjectMapping(
                "https://github.com/spring-petclinic/spring-petclinic-microservices", "{version}", "{file_path}")),
            List.of());
        GithubLinker linker = new GithubLinker(config, "https://github.com/should-not-be-used/fallback");

        String url = linker.link(PROJECT,
            "spring-petclinic-customers-service/src/main/java/.../OwnerResource.java", 89, 89);

        assertThat(url).isEqualTo("https://github.com/spring-petclinic/spring-petclinic-microservices/blob/"
            + "3858f9c630cf989bb6809a86edf47c2be78dc9f1/spring-petclinic-customers-service/src/main/java/.../"
            + "OwnerResource.java#L89");
    }

    @Test
    void multiLineStatementGetsAnEndLineAnchor() {
        CodeUnitsConfig config = new CodeUnitsConfig(
            Map.of("spring-petclinic-microservices", new CodeUnitsConfig.ProjectMapping(
                "https://github.com/spring-petclinic/spring-petclinic-microservices", "{version}", "{file_path}")),
            List.of());
        GithubLinker linker = new GithubLinker(config, null);

        String url = linker.link(PROJECT, "A.java", 10, 13);

        assertThat(url).endsWith("A.java#L10-L13");
    }

    @Test
    void projectFallsBackToGitRemoteWhenMappingHasNoRepo() {
        CodeUnitsConfig config = new CodeUnitsConfig(
            Map.of("spring-petclinic-microservices", new CodeUnitsConfig.ProjectMapping(null, "{version}", "{file_path}")),
            List.of());
        GithubLinker linker = new GithubLinker(config, "https://github.com/spring-petclinic/spring-petclinic-microservices");

        String url = linker.link(PROJECT, "A.java", 1, 1);

        assertThat(url).startsWith("https://github.com/spring-petclinic/spring-petclinic-microservices/blob/");
    }

    @Test
    void projectWithNoMappingAndNoFallbackReturnsNull() {
        CodeUnitsConfig config = new CodeUnitsConfig(Map.of(), List.of());
        GithubLinker linker = new GithubLinker(config, null);

        assertThat(linker.link(PROJECT, "A.java", 1, 1)).isNull();
    }

    @Test
    void dependencyExactMatchSubstitutesArtifactIdAndVersion() {
        CodeUnitsConfig config = new CodeUnitsConfig(Map.of(), List.of(
            new CodeUnitsConfig.DependencyMapping("com.zaxxer:HikariCP",
                "https://github.com/brettwooldridge/HikariCP", "HikariCP-{version}", "src/main/java/{file_path}")));
        GithubLinker linker = new GithubLinker(config, null);
        CodeUnit dependency = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "com.zaxxer:HikariCP", "7.0.2");

        String url = linker.link(dependency, "com/zaxxer/hikari/HikariConfig.java", 42, 42);

        assertThat(url).isEqualTo("https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/"
            + "src/main/java/com/zaxxer/hikari/HikariConfig.java#L42");
    }

    @Test
    void dependencyWildcardMatchUsesArtifactIdPlaceholder() {
        CodeUnitsConfig config = new CodeUnitsConfig(Map.of(), List.of(
            new CodeUnitsConfig.DependencyMapping("org.springframework:*",
                "https://github.com/spring-projects/spring-framework", "v{version}", "{artifactId}/src/main/java/{file_path}")));
        GithubLinker linker = new GithubLinker(config, null);
        CodeUnit dependency = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "org.springframework:spring-webmvc", "7.0.2");

        String url = linker.link(dependency, "org/springframework/web/servlet/DispatcherServlet.java", 5, 5);

        assertThat(url).isEqualTo("https://github.com/spring-projects/spring-framework/blob/v7.0.2/"
            + "spring-webmvc/src/main/java/org/springframework/web/servlet/DispatcherServlet.java#L5");
    }

    @Test
    void firstMatchingDependencyMappingWinsOverALaterOne() {
        CodeUnitsConfig config = new CodeUnitsConfig(Map.of(), List.of(
            new CodeUnitsConfig.DependencyMapping("org.springframework.boot:spring-boot",
                "https://github.com/spring-projects/spring-boot", "v{version}", "core/{artifactId}/src/main/java/{file_path}"),
            new CodeUnitsConfig.DependencyMapping("org.springframework.boot:spring-boot-*",
                "https://github.com/spring-projects/spring-boot", "v{version}", "module/{artifactId}/src/main/java/{file_path}")));
        GithubLinker linker = new GithubLinker(config, null);
        CodeUnit plainSpringBoot = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "org.springframework.boot:spring-boot", "4.0.1");
        CodeUnit actuator = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "org.springframework.boot:spring-boot-actuator", "4.0.1");

        assertThat(linker.link(plainSpringBoot, "org/springframework/boot/SpringApplication.java", 1, 1))
            .contains("/core/spring-boot/src/main/java/");
        assertThat(linker.link(actuator, "org/springframework/boot/actuate/health/HealthEndpoint.java", 1, 1))
            .contains("/module/spring-boot-actuator/src/main/java/");
    }

    @Test
    void dependencyWithNoMatchingMappingReturnsNull() {
        CodeUnitsConfig config = new CodeUnitsConfig(Map.of(), List.of(
            new CodeUnitsConfig.DependencyMapping("com.zaxxer:HikariCP", "https://github.com/brettwooldridge/HikariCP",
                "HikariCP-{version}", "src/main/java/{file_path}")));
        GithubLinker linker = new GithubLinker(config, null);
        CodeUnit unmapped = new CodeUnit(CodeUnit.TYPE_DEPENDENCY, "org.apache.tomcat.embed:tomcat-embed-core", "11.0.15");

        assertThat(linker.link(unmapped, "org/apache/catalina/core/StandardEngine.java", 1, 1)).isNull();
    }

    @Test
    void matchesGlobHandlesLiteralWildcardAndDots() {
        assertThat(GithubLinker.matchesGlob("com.zaxxer:HikariCP", "com.zaxxer:HikariCP")).isTrue();
        assertThat(GithubLinker.matchesGlob("com.zaxxer:HikariCP", "com.zaxxer:HikariCPX")).isFalse();
        assertThat(GithubLinker.matchesGlob("org.springframework:*", "org.springframework:spring-core")).isTrue();
        assertThat(GithubLinker.matchesGlob("org.springframework:*", "org.springframework.boot:spring-boot")).isFalse();
        assertThat(GithubLinker.matchesGlob("org.springframework.boot:spring-boot-*", "org.springframework.boot:spring-boot")).isFalse();
        assertThat(GithubLinker.matchesGlob("org.springframework.boot:spring-boot-*", "org.springframework.boot:spring-boot-actuator")).isTrue();
    }
}
