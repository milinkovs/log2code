package org.log2code.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Log2CodeEvalTest {

    @Test
    void exposesModuleName() {
        assertThat(Log2CodeEval.MODULE).isEqualTo("log2code-eval");
    }
}
