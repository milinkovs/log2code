package org.log2code.core.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.log2code.core.template.MessageTemplate.Hole;
import org.log2code.core.template.MessageTemplate.Literal;
import org.log2code.core.template.MessageTemplate.Part;

class MessageTemplateTest {

    // --- Group A: parse() / toNormalized() round trip on already-normalized strings ------------

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @MethodSource("normalizationCases")
    void parsesAndNormalizes(String input, String expectedCanonical) {
        assertThat(MessageTemplate.parse(input).toNormalized()).isEqualTo(expectedCanonical);
    }

    static Stream<Arguments> normalizationCases() {
        return Stream.of(
            Arguments.of("", ""),
            Arguments.of("{}", "{}"),
            Arguments.of("{} tail", "{} tail"),
            Arguments.of("head {}", "head {}"),
            Arguments.of("head {} tail", "head {} tail"),
            Arguments.of("  {}  ", "{}"),
            Arguments.of("   leading and trailing   ", "leading and trailing"),
            Arguments.of("no holes here", "no holes here"),
            Arguments.of("{}{}", "{}"),
            Arguments.of("a{}{}b", "a{}b"),
            Arguments.of("{} {} {}", "{} {} {}"),
            Arguments.of("   ", ""),
            Arguments.of("{}\t{}", "{}\t{}")
        );
    }

    // --- Group B: constructed via of(), escaping, unicode, multiline, brace-shaped values ------

    @Test
    void literalOpenBraceIsEscapedAndRoundTrips() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("{")));
        assertThat(t.toNormalized()).isEqualTo("\\{");
        assertThat(MessageTemplate.parse(t.toNormalized())).isEqualTo(t);
        assertThat(t.matchFull("{")).contains(List.of());
    }

    @Test
    void literalBackslashIsEscapedAndRoundTrips() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("\\")));
        assertThat(t.toNormalized()).isEqualTo("\\\\");
        assertThat(MessageTemplate.parse(t.toNormalized())).isEqualTo(t);
        assertThat(t.matchFull("\\")).contains(List.of());
    }

    @Test
    void literalContainingBraceShapedTextRoundTrips() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("map={} size="), new Hole()));
        assertThat(t.toNormalized()).isEqualTo("map=\\{} size={}");
        assertThat(MessageTemplate.parse(t.toNormalized())).isEqualTo(t);
        assertThat(t.matchFull("map={} size=100")).contains(List.of("100"));
    }

    @Test
    void holeValueMayItselfContainBraces() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("Value="), new Hole()));
        assertThat(t.matchFull("Value={weird}")).contains(List.of("{weird}"));
    }

    @Test
    void holeSpansMultipleLinesUnderDotall() {
        MessageTemplate t = MessageTemplate.of(
            List.of(new Literal("Exception: "), new Hole(), new Literal(" end")));
        assertThat(t.matchFull("Exception: line1\nline2 end")).contains(List.of("line1\nline2"));
    }

    @Test
    void handlesCyrillicLiterals() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("Власник "), new Hole()));
        assertThat(t.toNormalized()).isEqualTo("Власник {}");
        assertThat(t.matchFull("Власник Иван")).contains(List.of("Иван"));
    }

    @Test
    void handlesEmojiLiterals() {
        MessageTemplate t = MessageTemplate.of(
            List.of(new Literal("status "), new Hole(), new Literal(" 👍")));
        assertThat(t.toNormalized()).isEqualTo("status {} 👍");
        assertThat(t.matchFull("status ok 👍")).contains(List.of("ok"));
    }

    // --- Group C: literal parts neutralize regex metacharacters (Pattern.quote) ----------------

    @ParameterizedTest(name = "[{index}] literal=\"{0}\"")
    @MethodSource("regexMetacharCases")
    void literalPartsAreNotTreatedAsRegex(String literalText, String matchingMessage, String nonMatchingMessage) {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal(literalText)));
        assertThat(t.matchFull(matchingMessage)).as("exact literal text must match").contains(List.of());
        assertThat(t.matchFull(nonMatchingMessage))
            .as("regex-metacharacter reinterpretation must not happen")
            .isEmpty();
    }

    static Stream<Arguments> regexMetacharCases() {
        return Stream.of(
            Arguments.of("a.b", "a.b", "aXb"),
            Arguments.of("a*b", "a*b", "aaab"),
            Arguments.of("a+b", "a+b", "aab"),
            Arguments.of("a?b", "a?b", "b"),
            Arguments.of("a[bc]d", "a[bc]d", "abd"),
            Arguments.of("a{2}b", "a{2}b", "aab"),
            Arguments.of("a(b|c)d", "a(b|c)d", "abd"),
            Arguments.of("a|b", "a|b", "a"),
            Arguments.of("^abc$", "^abc$", "abc")
        );
    }

    // --- Group D: matchPrefix vs matchFull ------------------------------------------------------

    @Test
    void prefixMatchesLiteralOnlyTemplateWithAppendedText() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("ERROR: connection failed")));
        assertThat(t.matchPrefix("ERROR: connection failed and retrying")).contains(List.of());
        assertThat(t.matchFull("ERROR: connection failed and retrying")).isEmpty();
        assertThat(t.matchFull("ERROR: connection failed")).contains(List.of());
    }

    @Test
    void prefixMatchesTemplateWithHoleAndAppendedText() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("Value="), new Hole(), new Literal(" end")));
        assertThat(t.matchPrefix("Value=42 end extra stuff")).contains(List.of("42"));
        assertThat(t.matchFull("Value=42 end extra stuff")).isEmpty();
        assertThat(t.matchFull("Value=42 end")).contains(List.of("42"));
    }

    @Test
    void prefixDoesNotMatchWhenLiteralPrefixDiffers() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("Saving owner "), new Hole()));
        assertThat(t.matchPrefix("Deleting owner X")).isEmpty();
        assertThat(t.matchFull("Deleting owner X")).isEmpty();
    }

    @Test
    void prefixAndFullAgreeWhenMessageIsExact() {
        MessageTemplate t = MessageTemplate.of(List.of(new Literal("a"), new Hole(), new Literal("b")));
        assertThat(t.matchFull("aVALUEb")).contains(List.of("VALUE"));
        assertThat(t.matchPrefix("aVALUEb")).contains(List.of("VALUE"));
    }

    @Test
    void trailingHoleCapturesMinimallyUnderPrefixButFullyUnderFull() {
        MessageTemplate t = MessageTemplate.of(List.of(new Hole()));
        assertThat(t.matchPrefix("anything")).contains(List.of(""));
        assertThat(t.matchFull("anything")).contains(List.of("anything"));
    }

    // --- Group E: literalLength / holeCount / isDynamicOnly ------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("shapeCases")
    void computesLiteralLengthHoleCountAndDynamicOnly(
        String label, List<Part> parts, int expectedLiteralLength, int expectedHoleCount, boolean expectedDynamic
    ) {
        MessageTemplate t = MessageTemplate.of(parts);
        assertThat(t.literalLength()).as("literalLength").isEqualTo(expectedLiteralLength);
        assertThat(t.holeCount()).as("holeCount").isEqualTo(expectedHoleCount);
        assertThat(t.isDynamicOnly()).as("isDynamicOnly").isEqualTo(expectedDynamic);
    }

    static Stream<Arguments> shapeCases() {
        return Stream.of(
            Arguments.of("just a hole", List.of(new Hole()), 0, 1, true),
            Arguments.of("plain literal", List.of(new Literal("abc")), 3, 0, false),
            Arguments.of("whitespace-padded literal", List.of(new Literal("  a  ")), 1, 0, false),
            Arguments.of(
                "literal, hole, literal",
                List.of(new Literal("a "), new Hole(), new Literal(" b")), 2, 1, false
            ),
            Arguments.of(
                "whitespace-only literal between two holes (not trimmed, still dynamic)",
                List.of(new Hole(), new Literal("   "), new Hole()), 0, 2, true
            ),
            Arguments.of(
                "Saving owner {}",
                List.of(new Literal("Saving owner "), new Hole()), 11, 1, false
            )
        );
    }

    // --- Group F: constantTokens() combines and dedupes across literal parts -------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("constantTokensCases")
    void computesConstantTokens(String label, List<Part> parts, List<String> expectedTokens) {
        assertThat(MessageTemplate.of(parts).constantTokens()).containsExactlyElementsOf(expectedTokens);
    }

    static Stream<Arguments> constantTokensCases() {
        return Stream.of(
            Arguments.of(
                "Saving owner {}",
                List.of(new Literal("Saving owner "), new Hole()), List.of("saving", "owner")
            ),
            Arguments.of(
                "Error processing {} for request",
                List.of(new Literal("Error processing "), new Hole(), new Literal(" for request")),
                List.of("error", "processing", "for", "request")
            ),
            Arguments.of(
                "duplicate token across parts",
                List.of(new Literal("owner owner "), new Hole(), new Literal(" owner")), List.of("owner")
            ),
            Arguments.of("hole only, no literal tokens", List.of(new Hole()), List.of())
        );
    }

    // --- Miscellaneous -------------------------------------------------------------------------

    @Test
    void ofAndParseAgreeOnEquivalentInput() {
        MessageTemplate viaOf = MessageTemplate.of(List.of(new Literal("Saving owner "), new Hole()));
        MessageTemplate viaParse = MessageTemplate.parse("Saving owner {}");
        assertThat(viaOf).isEqualTo(viaParse);
        assertThat(viaOf.toRegex()).isEqualTo(viaParse.toRegex());
    }

    @Test
    void toRegexHasAnchorsAndNonGreedyHoles() {
        MessageTemplate t = MessageTemplate.parse("a {} b");
        assertThat(t.toRegex()).isEqualTo("^\\Qa \\E(.*?)\\Q b\\E$");
    }

    @Test
    void matchFullReturnsEmptyForNonMatchingMessage() {
        MessageTemplate t = MessageTemplate.parse("Saving owner {}");
        Optional<List<String>> result = t.matchFull("Deleting owner Owner[id=1]");
        assertThat(result).isEmpty();
    }
}
