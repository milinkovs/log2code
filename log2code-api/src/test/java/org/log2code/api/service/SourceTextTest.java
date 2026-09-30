package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceTextTest {

    private static final String CONTENT = String.join("\n", "package a;", "class Foo {", "  void bar() {", "    log.info(\"x\");", "  }", "}");

    @Test
    void extractRangeReturnsExactLinesAndClampsToTheFile() {
        assertThat(SourceText.extractRange(CONTENT, 3, 5)).isEqualTo("  void bar() {\n    log.info(\"x\");\n  }");
        assertThat(SourceText.extractRange(CONTENT, 5, 100)).isEqualTo("  }\n}");
        assertThat(SourceText.extractRange(CONTENT, 0, 3)).isNull();
        assertThat(SourceText.extractRange(CONTENT, 5, 3)).isNull();
        assertThat(SourceText.extractRange(CONTENT, 9, 12)).isNull();
        assertThat(SourceText.extractRange(null, 1, 3)).isNull();
    }

    @Test
    void extractSnippetReturnsRadiusAroundTheLine() {
        assertThat(SourceText.extractSnippet(CONTENT, 4, 1)).isEqualTo("  void bar() {\n    log.info(\"x\");\n  }");
        assertThat(SourceText.extractSnippet(CONTENT, 1, 1)).isEqualTo("package a;\nclass Foo {");
        assertThat(SourceText.extractSnippet(CONTENT, 99, 1)).isNull();
        assertThat(SourceText.extractSnippet(null, 1, 1)).isNull();
    }

    @Test
    void withLineNumbersRightAlignsTheNumbers() {
        assertThat(SourceText.withLineNumbers("first\nsecond", 87)).isEqualTo("  87 | first\n  88 | second");
    }

    @Test
    void withLineNumbersKeepsTheColumnWidthWhenTheNumbersGrowADigit() {
        assertThat(SourceText.withLineNumbers("a\nb\nc", 8)).isEqualTo("   8 | a\n   9 | b\n  10 | c");
    }

    @Test
    void withLineNumbersWidensTheColumnForFiveDigitLineNumbers() {
        assertThat(SourceText.withLineNumbers("a\nb", 9999)).isEqualTo(" 9999 | a\n10000 | b");
    }

    @Test
    void withLineNumbersKeepsBlankLinesAndIndentation() {
        assertThat(SourceText.withLineNumbers("  if (x) {\n\n  }", 1)).isEqualTo("   1 |   if (x) {\n   2 | \n   3 |   }");
    }

    @Test
    void withLineNumbersOfNullIsNull() {
        assertThat(SourceText.withLineNumbers(null, 1)).isNull();
    }
}
