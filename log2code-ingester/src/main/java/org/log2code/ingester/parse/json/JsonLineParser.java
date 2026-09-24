package org.log2code.ingester.parse.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import org.log2code.core.json.Json;
import org.log2code.core.model.Level;
import org.log2code.ingester.parse.HeaderFields;
import org.log2code.ingester.parse.LineParser;
import org.log2code.ingester.parse.LogFormatException;

/**
 * {@code type: json} (T17 step 5): one line is one complete, self-contained event, so there is no
 * multi-line assembly to do (unlike the text formats, which {@code EventAssembler}, T18, must
 * reassemble). {@code fields} (0.11) maps our canonical names to the JSON property names used by
 * the configured log source; every value is read as a flat top-level key (nested paths are not
 * supported). {@code pid}, {@code appName} and {@code correlationRaw} are always {@code null} here:
 * the 0.11 example schema has no equivalents, and trace/span come through directly instead.
 */
public final class JsonLineParser implements LineParser {

    private final ObjectMapper mapper = Json.mapper();
    private final String timestampField;
    private final String messageField;
    private final String levelField;
    private final String loggerField;
    private final String threadField;
    private final String stackTraceField;
    private final String traceIdField;
    private final String spanIdField;

    public JsonLineParser(Map<String, String> fields) {
        this.timestampField = requireField(fields, "timestamp");
        this.messageField = requireField(fields, "message");
        this.levelField = fields.get("level");
        this.loggerField = fields.get("logger");
        this.threadField = fields.get("thread");
        this.stackTraceField = fields.get("stack_trace");
        this.traceIdField = fields.get("trace_id");
        this.spanIdField = fields.get("span_id");
    }

    @Override
    public Optional<HeaderFields> parseHeader(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        JsonNode timestampNode = root.get(timestampField);
        Instant instant = parseInstant(timestampNode);
        String message = textOf(root, messageField);
        if (timestampNode == null || timestampNode.isNull() || instant == null || message == null) {
            return Optional.empty();
        }

        return Optional.of(new HeaderFields(
            timestampNode.asText(),
            instant,
            Level.parse(textOf(root, levelField)),
            null,
            null,
            textOf(root, threadField),
            null,
            textOf(root, loggerField),
            message,
            textOf(root, stackTraceField),
            textOf(root, traceIdField),
            textOf(root, spanIdField)));
    }

    /**
     * Reads a configured field as text. Handles the one real-world shape {@code asText()} gets
     * wrong for our purposes: some JSON log shippers (e.g. ECS-style multi-line stack traces)
     * send an array of strings rather than one string — {@code JsonNode.asText()} on an array or
     * object silently returns {@code ""} (not each element's text), which would make a stack
     * trace vanish. An array is joined with {@code "\n"}; anything else falls back to
     * {@code asText()} as before.
     */
    private static String textOf(JsonNode root, String fieldName) {
        if (fieldName == null) {
            return null;
        }
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return null;
            }
            StringBuilder joined = new StringBuilder();
            for (JsonNode element : node) {
                if (!joined.isEmpty()) {
                    joined.append('\n');
                }
                joined.append(element.asText());
            }
            return joined.toString();
        }
        return node.asText();
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Instant.ofEpochMilli(node.asLong());
        }
        String text = node.asText();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String requireField(Map<String, String> fields, String key) {
        String value = (fields == null) ? null : fields.get(key);
        if (value == null || value.isBlank()) {
            throw new LogFormatException("json format: 'fields." + key + "' is required");
        }
        return value;
    }
}
