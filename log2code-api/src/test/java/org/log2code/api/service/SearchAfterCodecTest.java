package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchAfterCodecTest {

    @Test
    void encodeThenDecodeRoundTrips() {
        List<String> sortValues = List.of("2026-09-24T10:00:00.000Z", "42", "abcdef0123456789");

        String cursor = SearchAfterCodec.encode(sortValues);
        List<String> decoded = SearchAfterCodec.decode(cursor);

        assertThat(decoded).isEqualTo(sortValues);
    }

    @Test
    void decodeRejectsGarbage() {
        assertThatThrownBy(() -> SearchAfterCodec.decode("not-valid-base64-json!!"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeRejectsBase64OfNonJsonArray() {
        String cursor = java.util.Base64.getEncoder().encodeToString("\"just a string\"".getBytes());
        assertThatThrownBy(() -> SearchAfterCodec.decode(cursor))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
