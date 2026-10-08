package org.log2code.eval.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Minimal RFC 4180 writer: UTF-8, LF line ends, fields quoted only when they contain a comma, quote or line break. */
final class Csv {

    private Csv() {
    }

    static void write(Path file, List<String> header, List<List<?>> rows) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append(line(header)).append('\n');
        for (List<?> row : rows) {
            out.append(line(row)).append('\n');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    static String line(List<?> fields) {
        return fields.stream().map(Csv::field).collect(Collectors.joining(","));
    }

    static String field(Object value) {
        String text = value == null ? "" : value instanceof Double d ? String.format(Locale.ROOT, "%.4f", d) : value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
