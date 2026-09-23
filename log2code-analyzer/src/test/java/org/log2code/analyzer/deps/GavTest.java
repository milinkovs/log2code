package org.log2code.analyzer.deps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GavTest {

    @Test
    void parsesGroupArtifactVersion() {
        Gav gav = Gav.parse("org.springframework:spring-webmvc:7.0.2");

        assertThat(gav.groupId()).isEqualTo("org.springframework");
        assertThat(gav.artifactId()).isEqualTo("spring-webmvc");
        assertThat(gav.version()).isEqualTo("7.0.2");
        assertThat(gav.groupArtifact()).isEqualTo("org.springframework:spring-webmvc");
    }

    @Test
    void rejectsCoordinatesWithoutExactlyTwoColons() {
        assertThatThrownBy(() -> Gav.parse("org.springframework:spring-webmvc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gav.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gav.parse(":a:1.0")).isInstanceOf(IllegalArgumentException.class);
    }
}
