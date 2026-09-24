package org.log2code.ingester.parse.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.Level;
import org.log2code.ingester.parse.HeaderFields;
import org.log2code.ingester.parse.LogFormatException;

/** T17 step 5: {@code type: json}, using the {@code example-json} mapping from {@code config/log-formats.yml}. */
class JsonLineParserTest {

    private static Map<String, String> exampleFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("timestamp", "@timestamp");
        fields.put("level", "level");
        fields.put("logger", "logger_name");
        fields.put("message", "message");
        fields.put("thread", "thread_name");
        fields.put("stack_trace", "stack_trace");
        fields.put("trace_id", "traceId");
        fields.put("span_id", "spanId");
        return fields;
    }

    private final JsonLineParser parser = new JsonLineParser(exampleFields());

    @Test
    void parsesAllConfiguredFields() {
        String line = """
            {"@timestamp":"2026-09-21T18:40:03.732Z","level":"INFO","logger_name":"o.s.s.p.customers.web.OwnerResource",\
            "message":"Saving owner [Owner id = 1]","thread_name":"nio-8081-exec-1",\
            "stack_trace":"java.lang.RuntimeException: boom\\n\\tat A.b(A.java:1)","traceId":"6ab17a03",\
            "spanId":"27023b9c"}""";

        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.timestampRaw()).isEqualTo("2026-09-21T18:40:03.732Z");
        assertThat(fields.instant()).isEqualTo(Instant.parse("2026-09-21T18:40:03.732Z"));
        assertThat(fields.level()).isEqualTo(Level.INFO);
        assertThat(fields.loggerRaw()).isEqualTo("o.s.s.p.customers.web.OwnerResource");
        assertThat(fields.messageFirstLine()).isEqualTo("Saving owner [Owner id = 1]");
        assertThat(fields.thread()).isEqualTo("nio-8081-exec-1");
        assertThat(fields.stackTrace()).startsWith("java.lang.RuntimeException: boom");
        assertThat(fields.traceId()).isEqualTo("6ab17a03");
        assertThat(fields.spanId()).isEqualTo("27023b9c");
        assertThat(fields.pid()).isNull();
        assertThat(fields.appName()).isNull();
        assertThat(fields.correlationRaw()).isNull();
    }

    @Test
    void joinsArrayStackTraceWithNewlines() {
        // Some JSON log shippers (ECS-style) send a multi-line stack trace as an array of lines
        // rather than one string; JsonNode.asText() on an array silently returns "", not each
        // element's text, so this must be handled explicitly (see JsonLineParser.textOf).
        String line = "{\"@timestamp\":\"2026-09-21T18:40:03.732Z\",\"message\":\"boom\","
            + "\"stack_trace\":[\"java.lang.RuntimeException: boom\",\"\\tat A.b(A.java:1)\"]}";

        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.stackTrace()).isEqualTo("java.lang.RuntimeException: boom\n\tat A.b(A.java:1)");
    }

    @Test
    void emptyArrayStackTraceIsNull() {
        String line = "{\"@timestamp\":\"2026-09-21T18:40:03.732Z\",\"message\":\"boom\",\"stack_trace\":[]}";

        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.stackTrace()).isNull();
    }

    @Test
    void acceptsEpochMillisTimestamp() {
        String line = "{\"@timestamp\":1758479403732,\"message\":\"hello\"}";

        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.instant()).isEqualTo(Instant.ofEpochMilli(1758479403732L));
        assertThat(fields.timestampRaw()).isEqualTo("1758479403732");
    }

    @Test
    void omittedOptionalFieldsAreNull() {
        String line = "{\"@timestamp\":\"2026-09-21T18:40:03.732Z\",\"message\":\"hello\"}";

        HeaderFields fields = parser.parseHeader(line).orElseThrow();

        assertThat(fields.level()).isEqualTo(Level.UNKNOWN);
        assertThat(fields.loggerRaw()).isNull();
        assertThat(fields.thread()).isNull();
        assertThat(fields.stackTrace()).isNull();
        assertThat(fields.traceId()).isNull();
        assertThat(fields.spanId()).isNull();
    }

    @Test
    void malformedJsonIsNotAHeader() {
        assertThat(parser.parseHeader("not json at all")).isEqualTo(Optional.empty());
        assertThat(parser.parseHeader("{\"@timestamp\": \"2026-09-21T18:40:03.732Z\", ")).isEqualTo(Optional.empty());
    }

    @Test
    void blankLineIsNotAHeader() {
        assertThat(parser.parseHeader("")).isEqualTo(Optional.empty());
        assertThat(parser.parseHeader("   ")).isEqualTo(Optional.empty());
    }

    @Test
    void jsonArrayIsNotAHeader() {
        assertThat(parser.parseHeader("[1,2,3]")).isEqualTo(Optional.empty());
    }

    @Test
    void missingRequiredTimestampFieldIsNotAHeader() {
        assertThat(parser.parseHeader("{\"message\":\"hello\"}")).isEqualTo(Optional.empty());
    }

    @Test
    void missingRequiredMessageFieldIsNotAHeader() {
        assertThat(parser.parseHeader("{\"@timestamp\":\"2026-09-21T18:40:03.732Z\"}")).isEqualTo(Optional.empty());
    }

    @Test
    void unparsableTimestampIsNotAHeader() {
        assertThat(parser.parseHeader("{\"@timestamp\":\"not-a-date\",\"message\":\"hello\"}")).isEqualTo(Optional.empty());
    }

    @Test
    void constructorRequiresTimestampAndMessageMapping() {
        Map<String, String> missingTimestamp = new LinkedHashMap<>(exampleFields());
        missingTimestamp.remove("timestamp");
        assertThatThrownBy(() -> new JsonLineParser(missingTimestamp))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("timestamp");

        Map<String, String> missingMessage = new LinkedHashMap<>(exampleFields());
        missingMessage.remove("message");
        assertThatThrownBy(() -> new JsonLineParser(missingMessage))
            .isInstanceOf(LogFormatException.class)
            .hasMessageContaining("message");
    }
}
