package org.log2code.ingester.parse.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Test-only helper: reads all lines of a {@code .log.gz} dataset file (T16), for T17 tests. */
public final class GzipLines {

    private GzipLines() {
    }

    public static List<String> read(Path gzFile) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gzFile))) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n", -1)) {
                lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            }
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
