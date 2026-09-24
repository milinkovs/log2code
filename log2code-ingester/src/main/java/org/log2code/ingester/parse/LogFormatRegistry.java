package org.log2code.ingester.parse;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.log2code.ingester.parse.config.LogFormatsConfig;
import org.log2code.ingester.parse.config.LogFormatsConfig.FormatEntry;
import org.log2code.ingester.parse.config.LogFormatsConfigLoader;
import org.log2code.ingester.parse.json.JsonLineParser;
import org.log2code.ingester.parse.logback.LogbackPatternCompiler;
import org.log2code.ingester.parse.preset.SpringBootDefaultParser;

/**
 * Builds one {@link LineParser} per {@code formats.<name>} entry of {@code config/log-formats.yml}
 * (0.11, T17 step 6) and hands them out by name — the name a dataset's {@code manifest.yml}
 * references via {@code log_format}.
 */
public final class LogFormatRegistry {

    private final Map<String, LineParser> parsers;

    private LogFormatRegistry(Map<String, LineParser> parsers) {
        this.parsers = parsers;
    }

    public static LogFormatRegistry load(Path configFile) {
        LogFormatsConfig config = LogFormatsConfigLoader.load(configFile);
        Map<String, LineParser> parsers = new LinkedHashMap<>();
        for (Map.Entry<String, FormatEntry> e : config.formats().entrySet()) {
            parsers.put(e.getKey(), build(e.getKey(), e.getValue()));
        }
        return new LogFormatRegistry(parsers);
    }

    public LineParser get(String name) {
        LineParser parser = parsers.get(name);
        if (parser == null) {
            throw new LogFormatException("unknown log format \"" + name + "\" (known: " + parsers.keySet() + ")");
        }
        return parser;
    }

    private static LineParser build(String name, FormatEntry entry) {
        String type = entry.type();
        if (type == null || type.isBlank()) {
            throw new LogFormatException("format \"" + name + "\": 'type' is required");
        }
        return switch (type) {
            case FormatEntry.TYPE_PRESET -> buildPreset(name);
            case FormatEntry.TYPE_LOGBACK_PATTERN -> LogbackPatternCompiler.compile(entry.pattern(), resolveZone(name, entry.timezone()));
            case FormatEntry.TYPE_JSON -> new JsonLineParser(entry.fields());
            default -> throw new LogFormatException("format \"" + name + "\": unknown type \"" + type + "\"");
        };
    }

    private static LineParser buildPreset(String name) {
        if ("spring-boot-default".equals(name)) {
            return new SpringBootDefaultParser();
        }
        throw new LogFormatException("format \"" + name + "\": unknown preset (only \"spring-boot-default\" exists)");
    }

    private static ZoneId resolveZone(String formatName, String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new LogFormatException("format \"" + formatName + "\": invalid 'timezone' \"" + timezone + "\"", e);
        }
    }
}
