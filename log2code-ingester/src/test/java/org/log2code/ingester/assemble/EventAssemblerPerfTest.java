package org.log2code.ingester.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.LogEvent;
import org.log2code.ingester.parse.preset.SpringBootDefaultParser;

/**
 * T18 AC2: parsing and assembling 100 000 lines must take &lt; 5 s. Disabled by default
 * (parent {@code pom.xml} excludes the {@code perf} JUnit tag from the normal surefire run) — run
 * explicitly with {@code ./mvnw -pl log2code-ingester test -Dgroups=perf}.
 */
@Tag("perf")
class EventAssemblerPerfTest {

    private static final int TOTAL_LINES = 100_000;
    private static final String THREAD = "nio-8081-exec-1"; // exactly 15 chars, as %15.15t requires
    private static final String LOGGER = String.format("%-40.40s", "o.s.s.p.customers.web.OwnerResource");
    private static final String CORRELATION = " ".repeat(49);

    @Test
    void assembles100000LinesInUnderFiveSeconds(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("perf.log");
        writeSyntheticLog(file, TOTAL_LINES);

        EventAssembler assembler = new EventAssembler(new SpringBootDefaultParser());
        AssemblyContext context = new AssemblyContext(
            "perf", "perf.log", "customers-service", "spring-petclinic-customers-service",
            new CodeVersion("spring-petclinic-microservices", "3858f9c630cf989bb6809a86edf47c2be78dc9f1"),
            "spring-boot-default", false);

        long start = System.nanoTime();
        List<LogEvent> events = assembler.assemble(file, context).toList();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("EventAssemblerPerfTest: " + TOTAL_LINES + " lines -> " + events.size()
            + " events in " + elapsedMs + " ms");

        assertThat(events).isNotEmpty();
        assertThat(elapsedMs).as("assembling %d lines", TOTAL_LINES).isLessThan(5000);
    }

    /**
     * Mostly single-line INFO events; every 20th is a 6-line ERROR block with a stack trace and a
     * "Caused by" cause, so the perf run also exercises {@link StackTraceParser}, not just header
     * matching. Total physical line count is exactly {@code totalLines}.
     */
    private static void writeSyntheticLog(Path file, int totalLines) throws IOException {
        List<String> lines = new ArrayList<>(totalLines + 16);
        int i = 0;
        while (lines.size() < totalLines) {
            if (i % 20 == 19) {
                lines.add(header(i, "ERROR", ""));
                lines.add("com.example.SyntheticException: synthetic failure " + i);
                lines.add("\tat com.example.Service.handle(Service.java:" + (100 + i % 900) + ")");
                lines.add("\tat com.example.Controller.dispatch(Controller.java:" + (50 + i % 50) + ")");
                lines.add("Caused by: com.example.RootCause: root " + i);
                lines.add("\tat com.example.Repo.query(Repo.java:" + (10 + i % 90) + ")");
            } else {
                lines.add(header(i, "INFO", "Saving owner [Owner@" + i + " id = " + i + "]"));
            }
            i++;
        }
        while (lines.size() > totalLines) {
            lines.remove(lines.size() - 1);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String header(int i, String level, String message) {
        return timestamp(i) + "  " + level + " 1 --- [customers-service] [" + THREAD + "] ["
            + CORRELATION + "] " + LOGGER + " : " + message;
    }

    private static String timestamp(int i) {
        int millis = i % 1000;
        int seconds = (i / 1000) % 60;
        int minutes = (i / 60_000) % 60;
        int hours = (i / 3_600_000) % 24;
        return String.format("2026-09-24T%02d:%02d:%02d.%03dZ", hours, minutes, seconds, millis);
    }
}
