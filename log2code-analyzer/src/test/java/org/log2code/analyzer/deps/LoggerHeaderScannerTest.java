package org.log2code.analyzer.deps;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Against realistic lines in the 0.9 format (see {@code docs/log-format.md}), including ones the regex must skip. */
class LoggerHeaderScannerTest {

    @Test
    void extractsServiceAndLoggerFromHeaderLinesOnly(@TempDir Path dir) throws IOException {
        Path logFile = dir.resolve("customers-service.log");
        Files.writeString(logFile, String.join("\r\n", List.of(
            "18:36:50,161 |-WARN in ch.qos.logback.core.model.processor.ImplicitModelHandler - Ignoring unknown property",
            "",
            " :: Spring Boot ::                (v4.0.1)",
            "",
            header("2026-09-21T18:40:03.732Z", "INFO", "customers-service", "nio-8081-exec-1",
                "6ab17a0317ae033034328c9faf2c8bf9-27023b9c90191af8", "o.s.s.p.customers.web.OwnerResource",
                "Saving owner [Owner@1b7d65ae id = 1]"),
            header("2026-09-21T18:40:25.912Z", "ERROR", "customers-service", "nio-8081-exec-2", "",
                "o.a.c.c.C.[.[.[/].[dispatcherServlet]", ""),
            "",
            "java.lang.RuntimeException: Chaos Monkey - RuntimeException",
            "\tat java.base/jdk.internal.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)",
            header("2026-09-21T18:40:04.000Z", "INFO", "customers-service", "nio-8081-exec-3", "",
                "o.s.s.p.customers.web.OwnerResource", "Saving owner [Owner@2 id = 2]")
        )) + "\r\n");

        LoggerHeaderScanner.Stats stats = LoggerHeaderScanner.scan(List.of(dir));

        Map<String, Integer> customers = stats.byService().get("customers-service");
        assertThat(customers).containsEntry("o.s.s.p.customers.web.OwnerResource", 2);
        assertThat(customers).containsEntry("o.a.c.c.C.[.[.[/].[dispatcherServlet]", 1);
        assertThat(stats.uniqueLoggers()).containsExactlyInAnyOrder(
            "o.s.s.p.customers.web.OwnerResource", "o.a.c.c.C.[.[.[/].[dispatcherServlet]");
    }

    @Test
    void missingLogDirectoryIsNotAnError() {
        LoggerHeaderScanner.Stats stats = LoggerHeaderScanner.scan(List.of(Path.of("does/not/exist")));

        assertThat(stats.uniqueLoggers()).isEmpty();
    }

    @Test
    void countsOccurrencesAcrossMultipleFilesInTheSameDirectory(@TempDir Path dir) throws IOException {
        String line = header("2026-09-21T18:40:03.732Z", "INFO", "vets-service", "main", "",
            "com.zaxxer.hikari.HikariDataSource", "Starting pool");
        Files.writeString(dir.resolve("a.log"), line + "\r\n");
        Files.writeString(dir.resolve("b.log"), line + "\r\n" + line + "\r\n");

        LoggerHeaderScanner.Stats stats = LoggerHeaderScanner.scan(List.of(dir));

        assertThat(stats.byService().get("vets-service")).containsEntry("com.zaxxer.hikari.HikariDataSource", 3);
    }

    /** Builds one 0.9-shaped header line; fields are padded/truncated to their exact fixed width. */
    private static String header(String timestamp, String level, String app, String thread,
                                  String correlation, String logger, String message) {
        return timestamp + "  " + level + " 1 --- [" + app + "] [" + fixedWidth(thread, 15) + "] ["
            + fixedWidth(correlation, 49) + "] " + fixedWidth(logger, 40) + " : " + message;
    }

    private static String fixedWidth(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }
}
