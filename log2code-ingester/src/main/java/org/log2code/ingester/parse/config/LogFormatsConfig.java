package org.log2code.ingester.parse.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Root of {@code config/log-formats.yml} (0.11, T17 step 1). */
public record LogFormatsConfig(Map<String, FormatEntry> formats) {

    public LogFormatsConfig {
        formats = (formats == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(formats));
    }

    /**
     * One {@code formats.<name>} entry. All fields except {@code type} are optional in YAML and
     * only meaningful for the matching {@code type} ({@code preset} uses none of them,
     * {@code logback-pattern} uses {@code pattern}/{@code timezone}, {@code json} uses
     * {@code fields}); {@link LogFormatsConfigLoader} validates that the required ones are present.
     */
    public record FormatEntry(String type, String pattern, String timezone, Map<String, String> fields) {

        public static final String TYPE_PRESET = "preset";
        public static final String TYPE_LOGBACK_PATTERN = "logback-pattern";
        public static final String TYPE_JSON = "json";

        public FormatEntry {
            fields = (fields == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }
}
