package org.log2code.analyzer.deps;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Against a real {@code mvn dependency:list -DoutputAbsoluteArtifactFilename=true} capture (PetClinic's
 * {@code spring-petclinic-vets-service}, includeScope=runtime): CRLF-terminated, with ANSI colour escapes
 * and a {@code -- module ... [auto]}/{@code (auto)} suffix after every jar path (T12 step 1).
 */
class DependencyListParserTest {

    @Test
    void parsesEveryArtifactLineFromARealCapture() throws IOException {
        List<Artifact> artifacts = DependencyListParser.parse(readFixture());

        assertThat(artifacts).hasSize(176);
        assertThat(artifacts).allSatisfy(a -> {
            assertThat(a.groupId()).isNotBlank();
            assertThat(a.artifactId()).isNotBlank();
            assertThat(a.version()).isNotBlank();
            assertThat(a.type()).isEqualTo("jar");
            assertThat(a.scope()).isIn("compile", "runtime");
            assertThat(a.jarPath().toString()).endsWith(".jar");
        });
    }

    @Test
    void extractsKnownRuntimeDependencies() throws IOException {
        List<Artifact> artifacts = DependencyListParser.parse(readFixture());

        assertThat(artifacts).extracting(Artifact::groupArtifact).contains(
            "org.springframework.boot:spring-boot",
            "org.springframework:spring-webmvc",
            "com.netflix.eureka:eureka-client",
            "org.springframework.cloud:spring-cloud-starter-netflix-eureka-client");
    }

    @Test
    void jarPathIsTheAbsoluteM2Path() throws IOException {
        List<Artifact> artifacts = DependencyListParser.parse(readFixture());

        Artifact springBoot = artifacts.stream().filter(a -> a.artifactId().equals("spring-boot")).findFirst().orElseThrow();
        assertThat(springBoot.gav()).isEqualTo("org.springframework.boot:spring-boot:4.0.1");
        assertThat(springBoot.jarPath().toString()).isEqualTo(
            "C:\\Users\\User\\.m2\\repository\\org\\springframework\\boot\\spring-boot\\4.0.1\\spring-boot-4.0.1.jar");
    }

    @Test
    void ignoresTheHeaderLine() throws IOException {
        List<Artifact> artifacts = DependencyListParser.parse(readFixture());

        assertThat(artifacts).noneMatch(a -> a.groupId().contains("following files"));
    }

    @Test
    void ignoresBlankAndUnrelatedLines() {
        List<Artifact> artifacts = DependencyListParser.parse(
            "The following files have been resolved:\r\n\r\n   not a valid artifact line\r\n");

        assertThat(artifacts).isEmpty();
    }

    private static String readFixture() throws IOException {
        try (InputStream in = DependencyListParserTest.class.getResourceAsStream("/fixtures/deps/vets-service-dependency-list.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
