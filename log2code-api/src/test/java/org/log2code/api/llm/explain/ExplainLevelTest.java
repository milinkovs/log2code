package org.log2code.api.llm.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExplainLevelTest {

    @Test
    void parsesTheFiveLevels() {
        assertThat(ExplainLevel.parse("L0")).isEqualTo(ExplainLevel.L0);
        assertThat(ExplainLevel.parse("L1")).isEqualTo(ExplainLevel.L1);
        assertThat(ExplainLevel.parse("L2")).isEqualTo(ExplainLevel.L2);
        assertThat(ExplainLevel.parse("L3")).isEqualTo(ExplainLevel.L3);
        assertThat(ExplainLevel.parse("L4")).isEqualTo(ExplainLevel.L4);
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[] {"L5", "l2", "2", "", " L2", "LEVEL", null}) {
            assertThatThrownBy(() -> ExplainLevel.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("level must be one of L0, L1, L2, L3, L4");
        }
    }

    @Test
    void levelsAreCumulative() {
        assertThat(ExplainLevel.L3.atLeast(ExplainLevel.L2)).isTrue();
        assertThat(ExplainLevel.L2.atLeast(ExplainLevel.L2)).isTrue();
        assertThat(ExplainLevel.L1.atLeast(ExplainLevel.L2)).isFalse();
    }

    @Test
    void theDefaultIsL2() {
        assertThat(ExplainLevel.DEFAULT).isEqualTo(ExplainLevel.L2);
    }
}
