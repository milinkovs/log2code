package org.log2code.ingester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Log2CodeIngesterTest {

    @Test
    void exposesModuleName() {
        assertThat(Log2CodeIngester.MODULE).isEqualTo("log2code-ingester");
    }
}
