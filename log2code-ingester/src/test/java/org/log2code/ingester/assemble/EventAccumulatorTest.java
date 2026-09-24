package org.log2code.ingester.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.ingester.parse.preset.SpringBootDefaultParser;

/**
 * T22 relies on {@link EventAccumulator} being drivable a few lines at a time across many separate
 * calls (poll cycles), not just in one continuous pass - and on being able to resume mid-file after a
 * restart. This exercises exactly that shape, checked against what a single-shot
 * {@link EventAssembler#assemble} produces for the same lines (ADR-023: the two must never drift).
 */
class EventAccumulatorTest {

    private static final CodeVersion CODE = new CodeVersion("spring-petclinic-microservices", "abc123");
    private static final String OWNER_FQN = "org.log2code.fixture.OwnerResource";

    private final EventAssembler assembler = new EventAssembler(new SpringBootDefaultParser());

    private static AssemblyContext context() {
        return new AssemblyContext("live", "x.log", "customers-service", "spring-petclinic-customers-service",
            CODE, "spring-boot-default", false);
    }

    private static String header(String timestamp, String level, String message) {
        return timestamp + "  " + level + " 1 --- [" + fixedWidth("nio-8081-exec-1", 15) + "] "
            + fixedWidth(OWNER_FQN, 40) + " : " + message;
    }

    private static String fixedWidth(String value, int width) {
        return value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
    }

    // ---- feeding lines one at a time matches a single-shot assemble() over the same content --------

    @Test
    void offeringLinesOneAtATimeAcrossManyCallsMatchesSingleShotAssembly() {
        String content = header("2026-09-24T10:00:00.000Z", "INFO", "Saving owner 42") + "\n"
            + header("2026-09-24T10:00:01.000Z", "ERROR", "boom") + "\n"
            + "java.lang.RuntimeException: boom\n"
            + "\tat org.log2code.fixture.OwnerResource.updateOwner(OwnerResource.java:89)\n";
        List<String> lines = content.lines().toList();

        // reference: single continuous pass, as EventAssembler.AssemblyIterator itself drives it
        EventAccumulator reference = assembler.newAccumulator(context());
        List<LogEvent> expected = new java.util.ArrayList<>();
        for (String line : lines) {
            reference.offer(line).ifPresent(expected::add);
        }
        reference.flushPending().ifPresent(expected::add);

        // under test: each line arrives in its own offer() call, simulating separate poll cycles
        // (some lines even split across "cycles" one line at a time, exactly like a real tailer)
        EventAccumulator underTest = assembler.newAccumulator(context());
        List<LogEvent> actual = new java.util.ArrayList<>();
        for (String line : lines) {
            underTest.offer(line).ifPresent(actual::add); // one "poll cycle" per line
        }
        // the exception event is still open (no next header, no EOF signal in follow mode) until flushed
        assertThat(underTest.hasPending()).isTrue();
        underTest.flushPending().ifPresent(actual::add);

        assertThat(actual).hasSize(2).isEqualTo(expected);
        assertThat(actual.get(0).message()).isEqualTo("Saving owner 42");
        assertThat(actual.get(1).message()).isEqualTo("boom");
        assertThat(actual.get(1).exception()).isNotNull();
        assertThat(actual.get(1).exception().frames()).hasSize(1);
    }

    // ---- a header line closes the previous buffer without needing flushPending() -------------------

    @Test
    void aFollowingHeaderClosesThePreviousEventWithoutAnExplicitFlush() {
        EventAccumulator accumulator = assembler.newAccumulator(context());
        accumulator.offer(header("2026-09-24T10:00:00.000Z", "INFO", "first"));
        assertThat(accumulator.hasPending()).isTrue();

        Optional<LogEvent> closed = accumulator.offer(header("2026-09-24T10:00:01.000Z", "INFO", "second"));
        assertThat(closed).isPresent();
        assertThat(closed.get().message()).isEqualTo("first");
        assertThat(accumulator.hasPending()).isTrue(); // "second" is now the open buffer
    }

    // ---- flushPending() force-closes an unfinished event (T22: idle flush-timeout / shutdown) -------

    @Test
    void flushPendingEmitsWhateverIsStillBufferedAndClearsIt() {
        EventAccumulator accumulator = assembler.newAccumulator(context());
        assertThat(accumulator.flushPending()).isEmpty(); // nothing buffered yet

        accumulator.offer(header("2026-09-24T10:00:00.000Z", "INFO", "half-written"));
        assertThat(accumulator.hasPending()).isTrue();

        Optional<LogEvent> flushed = accumulator.flushPending();
        assertThat(flushed).isPresent();
        assertThat(flushed.get().message()).isEqualTo("half-written");
        assertThat(accumulator.hasPending()).isFalse();
        assertThat(accumulator.flushPending()).isEmpty(); // idempotent once drained
    }

    // ---- resuming after a restart: line_number/sequence continue, not restart at 1/0 -----------------

    @Test
    void resumingFromANonZeroStartContinuesLineAndSequenceNumbering() {
        // as if lines 1-3 (one event) were already consumed/ingested in a previous run
        EventAccumulator resumed = assembler.newAccumulator(context(), 3, 1);

        Optional<LogEvent> closed = resumed.offer(header("2026-09-24T10:00:02.000Z", "INFO", "line four"));
        assertThat(closed).isEmpty(); // opens the buffer, nothing closes yet

        Optional<LogEvent> event = resumed.flushPending();
        assertThat(event).isPresent();
        assertThat(event.get().lineNumber()).isEqualTo(4);
        assertThat(event.get().sequence()).isEqualTo(1);
        assertThat(event.get().level()).isEqualTo(Level.INFO);
    }

    // ---- an orphan (no header seen yet) still gets built once flushed ---------------------------------

    @Test
    void linesBeforeAnyHeaderFormAnOrphanOnFlush() {
        EventAccumulator accumulator = assembler.newAccumulator(context());
        accumulator.offer("some warning printed before logging is configured");

        Optional<LogEvent> event = accumulator.flushPending();
        assertThat(event).isPresent();
        assertThat(event.get().level()).isEqualTo(Level.UNKNOWN);
        assertThat(event.get().loggerRaw()).isNull();
    }
}
