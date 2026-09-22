package org.log2code.core.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TokenizerTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("tokenCases")
    void tokenizes(String text, List<String> expected) {
        assertThat(Tokenizer.tokens(text)).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> tokenCases() {
        return Stream.of(
            Arguments.of(null, List.of()),
            Arguments.of("", List.of()),
            Arguments.of("   ", List.of()),
            Arguments.of("Saving owner", List.of("saving", "owner")),
            Arguments.of("SAVING OWNER", List.of("saving", "owner")),
            Arguments.of("a", List.of()),
            Arguments.of("ab", List.of("ab")),
            Arguments.of("123", List.of()),
            Arguments.of("12", List.of()),
            Arguments.of("abc123", List.of("abc123")),
            Arguments.of("123abc", List.of("123abc")),
            Arguments.of("owner, pet: id=42!", List.of("owner", "pet", "id")),
            Arguments.of("owner owner owner", List.of("owner")),
            Arguments.of("owner Owner OWNER", List.of("owner")),
            Arguments.of("foo   bar\tbaz\nqux", List.of("foo", "bar", "baz", "qux")),
            Arguments.of("владелец питомца", List.of("владелец", "питомца")),
            Arguments.of("id😀value", List.of("id", "value")),
            Arguments.of("re-try count=3", List.of("re", "try", "count")),
            Arguments.of("first second first", List.of("first", "second")),
            Arguments.of("v2.19.0", List.of("v2"))
        );
    }
}
