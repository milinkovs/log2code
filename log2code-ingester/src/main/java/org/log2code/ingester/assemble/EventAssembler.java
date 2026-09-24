package org.log2code.ingester.assemble;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.ingester.parse.AnsiCodes;
import org.log2code.ingester.parse.HeaderFields;
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

    private static final Pattern CORRELATION = Pattern.compile("^([0-9a-fA-F]{32})-([0-9a-fA-F]{16})$");

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
     * as it is complete — either because the next header line has started (so the current buffer
     * is done) or because the file ended. No lookahead buffering of the whole file is needed: T18's
     * assembly rule only requires knowing that the *next* header has begun to close out the
     * *current* event.
     */
    private final class AssemblyIterator implements Iterator<LogEvent> {

        private final BufferedReader reader;
        private final AssemblyContext context;

        private List<String> buffer = new ArrayList<>();
        private HeaderFields currentHeader;
        private int currentStartLine = 1;
        private int lineIndex = 0;
        private long sequence = 0;

        private LogEvent pending;
        private boolean pendingReady = false;
        private boolean readerAtEof = false;
        private boolean done = false;

        AssemblyIterator(BufferedReader reader, AssemblyContext context) {
            this.reader = reader;
            this.context = context;
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
                        lineIndex++;
                        Optional<HeaderFields> header = lineParser.parseHeader(line);
                        if (header.isPresent()) {
                            if (!buffer.isEmpty()) {
                                pending = buildEvent(currentHeader, buffer, currentStartLine, sequence++, context);
                                pendingReady = true;
                                currentHeader = header.get();
                                buffer = new ArrayList<>();
                                buffer.add(line);
                                currentStartLine = lineIndex;
                                return;
                            }
                            currentHeader = header.get();
                            buffer.add(line);
                            currentStartLine = lineIndex;
                        } else {
                            buffer.add(line);
                        }
                    }
                    readerAtEof = true;
                    closeQuietly(reader);
                }
                if (!buffer.isEmpty()) {
                    pending = buildEvent(currentHeader, buffer, currentStartLine, sequence++, context);
                    pendingReady = true;
                    buffer = List.of();
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

    private LogEvent buildEvent(
        HeaderFields header, List<String> rawLines, int lineNumber, long sequence, AssemblyContext ctx
    ) {
        List<String> textLines = toTextLines(header, rawLines);

        GroundTruth groundTruth = null;
        if (ctx.oracle() && !textLines.isEmpty()) {
            OracleMarker.Result marker = OracleMarker.strip(textLines.get(0), unreliableCallers);
            if (marker != null) {
                textLines.set(0, marker.remainder());
                groundTruth = marker.groundTruth();
            }
        }

        String message;
        ExceptionInfo exception;
        int exceptionStart = findExceptionStart(textLines);
        if (exceptionStart < 0) {
            message = String.join("\n", textLines).strip();
            exception = null;
        } else {
            message = String.join("\n", textLines.subList(0, exceptionStart)).strip();
            exception = StackTraceParser.parse(textLines.subList(exceptionStart, textLines.size()));
        }

        String traceId = null;
        String spanId = null;
        if (header != null) {
            traceId = blankToNull(header.traceId());
            spanId = blankToNull(header.spanId());
            if (traceId == null && spanId == null) {
                Matcher m = header.correlationRaw() == null
                    ? null
                    : CORRELATION.matcher(header.correlationRaw().trim());
                if (m != null && m.matches()) {
                    traceId = m.group(1);
                    spanId = m.group(2);
                }
            }
        }

        return new LogEvent(
            ctx.datasetId(),
            ctx.sourceFile(),
            lineNumber,
            rawLines.size(),
            sequence,
            header != null ? header.instant() : null,
            header != null ? header.timestampRaw() : null,
            ctx.service(),
            ctx.module(),
            header != null ? header.appName() : null,
            header != null ? header.pid() : null,
            header != null ? header.thread() : null,
            header != null ? header.level() : Level.UNKNOWN,
            header != null ? header.loggerRaw() : null,
            null,
            message,
            String.join("\n", rawLines),
            traceId,
            spanId,
            exception,
            ctx.code(),
            groundTruth,
            ctx.parserFormat());
    }

    /**
     * {@code lines[0]} is the header's own message ({@code null} header ⇒ the event is an
     * orphan, so every raw line is text); every other line is ANSI-stripped (defensively — real
     * Docker logs never carry ANSI codes, 0.9) before message/exception splitting.
     */
    private static List<String> toTextLines(HeaderFields header, List<String> rawLines) {
        List<String> result = new ArrayList<>(rawLines.size());
        if (header != null) {
            result.add(header.messageFirstLine());
            for (int i = 1; i < rawLines.size(); i++) {
                result.add(AnsiCodes.strip(rawLines.get(i)));
            }
        } else {
            for (String line : rawLines) {
                result.add(AnsiCodes.strip(line));
            }
        }
        return result;
    }

    /**
     * The first line matching {@code StackTraceParser.CLASS_LINE} immediately followed by a
     * frame, or — when the frames start with no class line at all (T18 step 2, see
     * {@code docs/log-format.md} §4) — the first frame line itself. {@code -1} when the event has
     * no stack trace.
     */
    private static int findExceptionStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (StackTraceParser.isFrameLine(lines.get(i))) {
                if (i > 0 && StackTraceParser.CLASS_LINE.matcher(lines.get(i - 1)).matches()) {
                    return i - 1;
                }
                return i;
            }
        }
        return -1;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
