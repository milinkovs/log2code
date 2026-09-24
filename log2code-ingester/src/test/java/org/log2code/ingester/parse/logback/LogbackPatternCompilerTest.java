package org.log2code.ingester.parse.logback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.Level;
import org.log2code.ingester.parse.HeaderFields;
import org.log2code.ingester.parse.LineParser;
import org.log2code.ingester.parse.LogFormatException;

/**
 * T17 step 4: at least 10 Logback conversion patterns, each with sample lines, covering every
 * conversion word / modifier the compiler must support.
 */
class LogbackPatternCompilerTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    // 1. The exact pattern from config/log-formats.yml's "example-logback" entry.
    @Test
    void pattern01_exampleLogback() {
        LineParser parser = LogbackPatternCompiler.compile(
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n", UTC);

        HeaderFields info = parser.parseHeader("2026-09-21 18:40:03.732 INFO  [main] com.example.Foo - hello world").orElseThrow();
        assertThat(info.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
        assertThat(info.level()).isEqualTo(Level.INFO);
        assertThat(info.thread()).isEqualTo("main");
        assertThat(info.loggerRaw()).isEqualTo("com.example.Foo");
        assertThat(info.messageFirstLine()).isEqualTo("hello world");

        HeaderFields error = parser.parseHeader("2026-09-21 18:40:04.001 ERROR [nio-8081-exec-1] o.s.s.p.c.OwnerResource - boom").orElseThrow();
        assertThat(error.level()).isEqualTo(Level.ERROR);
        assertThat(error.thread()).isEqualTo("nio-8081-exec-1");

        assertThat(parser.parseHeader("\tat java.base/java.lang.Thread.run(Thread.java:840)")).isEmpty();
    }

    // 2. Bare %date (no {fmt}): Logback's own default, comma-separated millis.
    @Test
    void pattern02_bareDateUsesLogbackDefaultFormat() {
        LineParser parser = LogbackPatternCompiler.compile("%date %level %msg%n", UTC);

        HeaderFields fields = parser.parseHeader("2026-09-21 18:40:03,732 INFO hello").orElseThrow();
        assertThat(fields.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
        assertThat(fields.level()).isEqualTo(Level.INFO);
        assertThat(fields.messageFirstLine()).isEqualTo("hello");
    }

    // 3. Quoted literal ('T') inside the date format, plus an explicit ISO offset (XXX).
    @Test
    void pattern03_quotedLiteralAndIsoOffset() {
        LineParser parser = LogbackPatternCompiler.compile(
            "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %p %m%n", UTC);

        HeaderFields fields = parser.parseHeader("2026-09-21T20:40:03.732+02:00 INFO hello").orElseThrow();
        assertThat(fields.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
        assertThat(fields.level()).isEqualTo(Level.INFO);

        HeaderFields zulu = parser.parseHeader("2026-09-21T18:40:03.732Z INFO hello").orElseThrow();
        assertThat(zulu.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
    }

    // 4. %C/%class, %M/%method, %L/%line: recognized and matched, but not surfaced in HeaderFields.
    @Test
    void pattern04_classMethodLineAreMatchedButNotSurfaced() {
        LineParser parser = LogbackPatternCompiler.compile(
            "%d{yyyy-MM-dd HH:mm:ss} %c %C %M %L %m%n", UTC);

        HeaderFields fields = parser.parseHeader(
            "2026-09-21 18:40:03 com.example.Foo com.example.Foo save 42 hello").orElseThrow();
        assertThat(fields.loggerRaw()).isEqualTo("com.example.Foo");
        assertThat(fields.messageFirstLine()).isEqualTo("hello");
    }

    // 5. %X{key}: matched (so the pattern compiles and lines with an MDC value still parse),
    //    but HeaderFields has no generic MDC slot to carry the value into.
    @Test
    void pattern05_mdcKeyIsMatchedButNotSurfaced() {
        LineParser parser = LogbackPatternCompiler.compile(
            "%d{yyyy-MM-dd HH:mm:ss} [%X{traceId}] %msg%n", UTC);

        HeaderFields withTrace = parser.parseHeader("2026-09-21 18:40:03 [6ab17a03] hello").orElseThrow();
        assertThat(withTrace.messageFirstLine()).isEqualTo("hello");

        HeaderFields withoutTrace = parser.parseHeader("2026-09-21 18:40:03 [] hello").orElseThrow();
        assertThat(withoutTrace.messageFirstLine()).isEqualTo("hello");
    }

    // 6. %pid.
    @Test
    void pattern06_pidConversionWord() {
        LineParser parser = LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} %pid %msg%n", UTC);

        HeaderFields fields = parser.parseHeader("2026-09-21 18:40:03 1 hello").orElseThrow();
        assertThat(fields.pid()).isEqualTo("1");
    }

    // 7. ${PID}: Spring's property-substitution spelling of the same field.
    @Test
    void pattern07_dollarBracePidPlaceholder() {
        LineParser parser = LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} ${PID:-} %msg%n", UTC);

        HeaderFields fields = parser.parseHeader("2026-09-21 18:40:03 1234 hello").orElseThrow();
        assertThat(fields.pid()).isEqualTo("1234");
    }

    // 8. %clr(...){...}: only the wrapped content is compiled (color name discarded), and it
    //    applies to several fields at once, mirroring Boot's own pattern shape.
    @Test
    void pattern08_colorWrapperKeepsOnlyItsContent() {
        LineParser parser = LogbackPatternCompiler.compile(
            "%clr(%d{yyyy-MM-dd HH:mm:ss}){faint} %clr(%-5level){red} %clr(%logger){cyan}: %msg%n", UTC);

        HeaderFields fields = parser.parseHeader("2026-09-21 18:40:03 INFO  com.example.Foo: hello").orElseThrow();
        assertThat(fields.level()).isEqualTo(Level.INFO);
        assertThat(fields.loggerRaw()).isEqualTo("com.example.Foo");
        assertThat(fields.messageFirstLine()).isEqualTo("hello");
    }

    // 9. Fixed width (min == max), Boot's own "%15.15thread" / "%-40.40logger" idiom: the field
    //    always occupies exactly that many characters in the line (Logback already padded or
    //    truncated it when writing); the parser just extracts that exact slot and trims padding.
    @Test
    void pattern09_fixedWidthExtractsExactSlot() {
        LineParser parser = LogbackPatternCompiler.compile(
            "%d{yyyy-MM-dd HH:mm:ss} [%15.15thread] %-20.20logger %msg%n", UTC);

        // "main" padded (left, right-justified) to 15; "short" padded (right, left-justified) to 20.
        HeaderFields padded = parser.parseHeader("2026-09-21 18:40:03 [           main] short                hello").orElseThrow();
        assertThat(padded.thread()).isEqualTo("main");
        assertThat(padded.loggerRaw()).isEqualTo("short");

        // A 15-char thread name exactly, and a 20-char logger slot as Logback would have truncated it.
        HeaderFields fullWidth = parser.parseHeader(
            "2026-09-21 18:40:03 [nio-8081-exec-1] e.VeryLongLoggerName hello").orElseThrow();
        assertThat(fullWidth.thread()).isEqualTo("nio-8081-exec-1");
        assertThat(fullWidth.loggerRaw()).isEqualTo("e.VeryLongLoggerName");
    }

    // 10. Min-width-only ("%-5level"): still pads shorter values, but isn't a fixed-width field;
    //     the padding must be tolerated wherever it lands.
    @Test
    void pattern10_minWidthOnlyTolerantOfPadding() {
        LineParser parser = LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} %-5level %msg%n", UTC);

        assertThat(parser.parseHeader("2026-09-21 18:40:03 INFO  hello").orElseThrow().level()).isEqualTo(Level.INFO);
        assertThat(parser.parseHeader("2026-09-21 18:40:03 WARN  hello").orElseThrow().level()).isEqualTo(Level.WARN);
        assertThat(parser.parseHeader("2026-09-21 18:40:03 ERROR hello").orElseThrow().level()).isEqualTo(Level.ERROR);
    }

    // 11. A configured timezone is used when the date format itself carries no zone/offset info.
    @Test
    void pattern11_usesConfiguredTimezoneWhenPatternHasNone() {
        LineParser parser = LogbackPatternCompiler.compile(
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n", ZoneId.of("+02:00"));

        HeaderFields fields = parser.parseHeader("2026-09-21 20:40:03.732 hello").orElseThrow();
        assertThat(fields.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
    }

    // 12. %%: a literal percent sign inside the pattern text.
    @Test
    void pattern12_literalPercentEscape() {
        LineParser parser = LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} 100%% %msg%n", UTC);

        HeaderFields fields = parser.parseHeader("2026-09-21 18:40:03 100% hello").orElseThrow();
        assertThat(fields.messageFirstLine()).isEqualTo("hello");
    }

    // ---- errors -------------------------------------------------------------------------------

    @Test
    void unsupportedConversionWordFailsToLoad() {
        assertThatThrownBy(() -> LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} %unknown %msg%n", UTC))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("unsupported conversion word");
    }

    @Test
    void mdcWithoutKeyFailsToLoad() {
        assertThatThrownBy(() -> LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} %X %msg%n", UTC))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("MDC key");
    }

    @Test
    void patternWithoutTimestampFailsToLoad() {
        assertThatThrownBy(() -> LogbackPatternCompiler.compile("%level %msg%n", UTC))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("timestamp");
    }

    @Test
    void patternWithoutMessageFailsToLoad() {
        assertThatThrownBy(() -> LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} %level%n", UTC))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("message");
    }

    @Test
    void unsupportedDatePatternLetterFailsToLoad() {
        assertThatThrownBy(() -> LogbackPatternCompiler.compile("%d{yyyy-QQ-dd} %msg%n", UTC))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("unsupported letter");
    }

    @Test
    void unsupportedPropertyPlaceholderFailsToLoad() {
        assertThatThrownBy(() -> LogbackPatternCompiler.compile("%d{yyyy-MM-dd HH:mm:ss} ${HOSTNAME} %msg%n", UTC))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("HOSTNAME");
    }

    @Test
    void blankPatternFailsToLoad() {
        assertThatThrownBy(() -> LogbackPatternCompiler.compile("  ", UTC))
            .isInstanceOf(LogFormatException.class);
    }
}
