package org.log2code.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Log2CodeCoreTest {

    @Test
    void exposesModuleName() {
        assertThat(Log2CodeCore.MODULE).isEqualTo("log2code-core");
    }
}
