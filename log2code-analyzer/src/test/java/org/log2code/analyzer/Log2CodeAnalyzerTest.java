package org.log2code.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Log2CodeAnalyzerTest {

    @Test
    void exposesModuleName() {
        assertThat(Log2CodeAnalyzer.MODULE).isEqualTo("log2code-analyzer");
    }
}
