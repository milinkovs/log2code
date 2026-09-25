package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LogSearchParamsTest {

    @Test
    void defaultsOrderToDescWhenNotGiven() {
        LogSearchParams params = params(null, 100);
        assertThat(params.order()).isEqualTo("desc");
    }

    @Test
    void rejectsAnOrderThatIsNotAscOrDesc() {
        assertThatThrownBy(() -> params("sideways", 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clampsSizeAboveMaxDownTo500() {
        LogSearchParams params = params("asc", 10_000);
        assertThat(params.size()).isEqualTo(LogSearchParams.MAX_SIZE);
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> params("asc", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> params("asc", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullMultiValuedListsBecomeEmptyNotNull() {
        LogSearchParams params = new LogSearchParams(null, null, null, null, null, null, null, null, null, null,
            null, 100, null, null);
        assertThat(params.service()).isEmpty();
        assertThat(params.level()).isEmpty();
        assertThat(params.status()).isEmpty();
        assertThat(params.confidenceLevel()).isEmpty();
    }

    private static LogSearchParams params(String order, int size) {
        return new LogSearchParams(null, null, null, null, null, null, null, null, null, null, null, size, null, order);
    }
}
