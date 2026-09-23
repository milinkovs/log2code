package org.log2code.analyzer.deps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts {@code (service, logger_raw)} pairs from log header lines (T12 step 2), using the simple regex
 * from {@code docs/log-format.md} (0.9) rather than the full parser, which is T17's job. Only header lines
 * match; stack trace lines, the Spring banner and logback status lines are silently skipped.
 */
public final class LoggerHeaderScanner {

    private static final Pattern HEADER = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})) +([A-Z]+) (\\d+) --- "
            + "\\[([^\\]]*)\\] \\[(.{15})\\] \\[(.{49})\\] (.{40}) : (.*)$");

    private LoggerHeaderScanner() {
    }

    /** {@code service -> loggerRaw -> occurrence count}, built from every log file under {@code logDirs}. */
    public static final class Stats {
        private final Map<String, Map<String, Integer>> byService = new TreeMap<>();

        void record(String service, String loggerRaw) {
            byService.computeIfAbsent(service, s -> new LinkedHashMap<>()).merge(loggerRaw, 1, Integer::sum);
        }

        public Map<String, Map<String, Integer>> byService() {
            return byService;
        }

        /** Every distinct {@code logger_raw} seen, across all services (used for the AC4 coverage percentage). */
        public Set<String> uniqueLoggers() {
            Set<String> all = new TreeSet<>();
            byService.values().forEach(m -> all.addAll(m.keySet()));
            return all;
        }
    }

    public static Stats scan(List<Path> logDirs) {
        Stats stats = new Stats();
        for (Path dir : logDirs) {
            if (!Files.isDirectory(dir)) {
                continue; // 0.11: auto-select-from-logs entries are best-effort; a missing dir is not an error
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    scanFile(file, stats);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed to scan log directory " + dir, e);
            }
        }
        return stats;
    }

    private static void scanFile(Path file, Stats stats) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
        for (String line : lines) {
            Matcher matcher = HEADER.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String service = matcher.group(4).trim();
            String loggerRaw = matcher.group(7).trim();
            if (!service.isEmpty() && !loggerRaw.isEmpty()) {
                stats.record(service, loggerRaw);
            }
        }
    }
}
