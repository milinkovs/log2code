package org.log2code.ingester.assemble;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import org.log2code.core.model.LogEvent;
import org.log2code.ingester.parse.LineParser;

/**
 * Assembles the physical lines of one {@code .log}/{@code .log.gz} dataset file (T18) into
 * {@link LogEvent}s, one per log event:
 *
 * <ul>
 *   <li>a line the configured {@link LineParser} recognizes as a header starts a new event; every
 *       other line is appended to the current event (multiline messages, stack traces);</li>
 *   <li>lines before the first header form one "orphan" event with {@code level = UNKNOWN}
 *       (T18 step 1) — never dropped;</li>
 *   <li>the event's message is split from any Java stack trace it carries (T18 step 2/3) and,
 *       for oracle datasets, from the {@code @@L2C[...]@@} marker (T18 step 5);</li>
 *   <li>{@code trace_id}/{@code span_id} come straight from the header for JSON, or are parsed out
 *       of the correlation segment for the text-based formats (T18 step 4).</li>
 * </ul>
 *
 * <p>Matching against the catalog (T19–T21) — including resolving {@code logger} to a FQN and
 * enriching stack frames with {@code code_unit}/{@code file_id}/{@code github_url} — happens later
 * and is out of scope here; those fields are left {@code null}/{@code false}.
 *
 * <p>The per-line "when is a buffered run of lines a complete event" decision and the actual
 * line-list → {@link LogEvent} construction live in {@link EventAccumulator} (ADR-023) — shared with
 * T22's live follow mode, which drives the same accumulator incrementally across poll cycles instead
 * of reading a file to EOF in one pass.
 *
 * <p><b>Memory:</b> {@link #assemble(Path, AssemblyContext)} reads the file lazily, one physical
 * line at a time (a {@link BufferedReader} over a {@link GZIPInputStream} for {@code .log.gz}) —
 * it never materializes the whole file. Peak memory is bounded by the largest single event (its
 * longest stack trace), not by file size, which matters once real datasets grow well past the
 * kilobyte-sized ones recorded so far (0.15's memory budget leaves little headroom for a host-side
 * CLI). Like {@link Files#lines(Path)}, the returned stream holds a file handle open until it is
 * either drained to the end (the normal case: iteration itself closes the reader on EOF) or
 * explicitly closed — callers that may stop early (e.g. {@code findFirst()}) should use it in a
 * try-with-resources block.
 */
public final class EventAssembler {

    private final LineParser lineParser;
    private final Set<String> unreliableCallers;

    public EventAssembler(LineParser lineParser) {
        this(lineParser, UnreliableCallers.DEFAULT);
    }

    public EventAssembler(LineParser lineParser, Set<String> unreliableCallers) {
        this.lineParser = Objects.requireNonNull(lineParser, "lineParser");
        this.unreliableCallers = Objects.requireNonNull(unreliableCallers, "unreliableCallers");
    }

    public Stream<LogEvent> assemble(Path file, AssemblyContext context) {
        BufferedReader reader = openReader(file);
        Iterator<LogEvent> iterator = new AssemblyIterator(reader, context);
        Spliterator<LogEvent> spliterator = Spliterators.spliteratorUnknownSize(
            iterator, Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false).onClose(() -> closeQuietly(reader));
    }

    /**
     * A fresh {@link EventAccumulator} for {@code context}, starting at line 1/sequence 0 - the same
     * one {@link #assemble} drives internally, exposed so T22's follow mode (a different package) can
     * build one without duplicating how {@code lineParser}/{@code unreliableCallers} feed it.
     */
    public EventAccumulator newAccumulator(AssemblyContext context) {
        return new EventAccumulator(lineParser, unreliableCallers, context);
    }

    /** Like {@link #newAccumulator(AssemblyContext)}, but resuming at a non-zero line/sequence (T22 restart). */
    public EventAccumulator newAccumulator(AssemblyContext context, int startLineIndex, long startSequence) {
        return new EventAccumulator(lineParser, unreliableCallers, context, startLineIndex, startSequence);
    }

    private static BufferedReader openReader(Path file) {
        try {
            InputStream raw = Files.newInputStream(file);
            InputStream in = file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        try {
            reader.close();
        } catch (IOException e) {
            // nothing sensible to do on close failure of a read-only stream
        }
    }

    /**
     * Pulls physical lines from {@code reader} one at a time and yields a {@link LogEvent} as soon
     * as {@link EventAccumulator} reports one is complete — either because the next header line has
     * started (so the current buffer is done) or because the file ended. No lookahead buffering of
     * the whole file is needed: T18's assembly rule only requires knowing that the *next* header has
     * begun to close out the *current* event.
     */
    private final class AssemblyIterator implements Iterator<LogEvent> {

        private final BufferedReader reader;
        private final EventAccumulator accumulator;

        private LogEvent pending;
        private boolean pendingReady = false;
        private boolean readerAtEof = false;
        private boolean done = false;

        AssemblyIterator(BufferedReader reader, AssemblyContext context) {
            this.reader = reader;
            this.accumulator = newAccumulator(context);
        }

        @Override
        public boolean hasNext() {
            advance();
            return pendingReady;
        }

        @Override
        public LogEvent next() {
            advance();
            if (!pendingReady) {
                throw new NoSuchElementException();
            }
            LogEvent result = pending;
            pending = null;
            pendingReady = false;
            return result;
        }

        private void advance() {
            if (pendingReady || done) {
                return;
            }
            try {
                if (!readerAtEof) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Optional<LogEvent> ready = accumulator.offer(line);
                        if (ready.isPresent()) {
                            pending = ready.get();
                            pendingReady = true;
                            return;
                        }
                    }
                    readerAtEof = true;
                    closeQuietly(reader);
                }
                Optional<LogEvent> last = accumulator.flushPending();
                if (last.isPresent()) {
                    pending = last.get();
                    pendingReady = true;
                } else {
                    done = true;
                }
            } catch (IOException e) {
                closeQuietly(reader);
                done = true;
                throw new UncheckedIOException(e);
            }
        }
    }
}
