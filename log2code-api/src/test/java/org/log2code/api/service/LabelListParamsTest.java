package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.log2code.core.model.Label;

class LabelListParamsTest {

    @Test
    void acceptsEachKnownVerdict() {
        assertThat(new LabelListParams("smoke-01", Label.VERDICT_CORRECT, 50, null).verdict()).isEqualTo("correct");
        assertThat(new LabelListParams("smoke-01", Label.VERDICT_INCORRECT, 50, null).verdict()).isEqualTo("incorrect");
        assertThat(new LabelListParams("smoke-01", Label.VERDICT_NOT_IN_CATALOG, 50, null).verdict()).isEqualTo("not_in_catalog");
    }

    @Test
    void rejectsAnUnknownVerdict() {
        assertThatThrownBy(() -> new LabelListParams("smoke-01", "maybe", 50, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verdict");
    }

    @Test
    void nullOrBlankVerdictMeansNoFilter() {
        assertThat(new LabelListParams("smoke-01", null, 50, null).verdict()).isNull();
        assertThat(new LabelListParams("smoke-01", "", 50, null).verdict()).isEmpty();
    }

    @Test
    void clampsSizeAboveMaxDownTo500() {
        assertThat(new LabelListParams(null, null, 10_000, null).size()).isEqualTo(LabelListParams.MAX_SIZE);
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> new LabelListParams(null, null, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LabelListParams(null, null, -1, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
