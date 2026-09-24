package org.log2code.ingester.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnsiCodesTest {

    @Test
    void leavesPlainLinesUntouched() {
        String line = "2026-09-21T18:40:03.732Z  INFO 1 --- [customers-service] Saving owner test";
        assertThat(AnsiCodes.strip(line)).isSameAs(line); // fast path: no ESC byte, no copy at all
    }

    @Test
    void stripsCsiSequences() {
        assertThat(AnsiCodes.strip("\u001B[0;39mhello\u001B[0;39m world")).isEqualTo("hello world");
        assertThat(AnsiCodes.strip("\u001B[32mINFO\u001B[0;39m")).isEqualTo("INFO");
    }

    @Test
    void handlesEmptyAndEscOnlyInput() {
        assertThat(AnsiCodes.strip("")).isEmpty();
        assertThat(AnsiCodes.strip("\u001B")).isEqualTo("\u001B"); // lone ESC, not a full CSI sequence
    }
}
