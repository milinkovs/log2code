package org.log2code.ingester.assemble;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Level;
import org.log2code.core.model.LogEvent;
import org.log2code.ingester.parse.AnsiCodes;
import org.log2code.ingester.parse.HeaderFields;
import org.log2code.ingester.parse.LineParser;

/**
 * The reusable core of T18's assembly rule (ADR-023): given physical lines fed one at a time via
 * {@link #offer(String)}, decides when a buffered run of lines is a complete {@link LogEvent} - a
 * line the {@link LineParser} recognizes as a header closes out whatever was buffered before it - and
 * builds that event (message/exception split, oracle marker, trace id, T18 steps 1-5).
 *
 * <p>Extracted from {@link EventAssembler} so the same logic drives both a one-shot, read-to-EOF pass
 * over a static dataset file ({@link EventAssembler#assemble(java.nio.file.Path, AssemblyContext)}) and
 * T22's live tailing, where lines arrive incrementally across poll cycles and the "current" buffer must
 * stay open - not force-flushed - between calls, only closing on the next header or an explicit
 * {@link #flushPending()} (EOF in batch mode; the next header or a flush-timeout in follow mode). Not
 * thread-safe: like {@link EventAssembler}, one instance is driven by a single thread.
 */
public final class EventAccumulator {

    private static final Pattern CORRELATION = Pattern.compile("^([0-9a-fA-F]{32})-([0-9a-fA-F]{16})$");

    private final LineParser lineParser;
    private final Set<String> unreliableCallers;
    private final AssemblyContext context;

    private List<String> buffer = new ArrayList<>();
    private HeaderFields currentHeader;
    private int currentStartLine;
    private int lineIndex;
    private long sequence;

    /** Starts counting lines/events from 1/0 - {@link EventAssembler}'s one-shot batch use. */
    public EventAccumulator(LineParser lineParser, Set<String> unreliableCallers, AssemblyContext context) {
        this(lineParser, unreliableCallers, context, 0, 0);
    }

    /**
     * Starts counting from {@code startLineIndex}/{@code startSequence} instead of 0 - T22's follow
     * mode resumes a file at a non-zero line/sequence after a restart (0.7/0.8: {@code line_number}
     * and {@code log_id} must match what they would have been had the process never stopped).
     */
    public EventAccumulator(LineParser lineParser, Set<String> unreliableCallers, AssemblyContext context,
                             int startLineIndex, long startSequence) {
        this.lineParser = Objects.requireNonNull(lineParser, "lineParser");
        this.unreliableCallers = Objects.requireNonNull(unreliableCallers, "unreliableCallers");
        this.context = Objects.requireNonNull(context, "context");
        this.lineIndex = startLineIndex;
        this.currentStartLine = startLineIndex + 1;
        this.sequence = startSequence;
    }

    /** True while a buffer is open (not yet closed by a header or {@link #flushPending()}). */
    public boolean hasPending() {
        return !buffer.isEmpty();
    }

    /**
     * Feeds one physical line. Returns the event the *previous* buffer closed into, if this line is a
     * header and a buffer was already open; otherwise the line extends the (possibly new) current
     * buffer and this returns empty - the caller finds out once a later header arrives, EOF is
     * reached (batch mode), or {@link #flushPending()} is called explicitly (follow mode).
     */
    public Optional<LogEvent> offer(String line) {
        lineIndex++;
        Optional<HeaderFields> header = lineParser.parseHeader(line);
        if (header.isPresent()) {
            if (!buffer.isEmpty()) {
                LogEvent event = buildEvent(currentHeader, buffer, currentStartLine, sequence++);
                currentHeader = header.get();
                buffer = new ArrayList<>();
                buffer.add(line);
                currentStartLine = lineIndex;
                return Optional.of(event);
            }
            currentHeader = header.get();
            buffer.add(line);
            currentStartLine = lineIndex;
            return Optional.empty();
        }
        buffer.add(line);
        return Optional.empty();
    }

    /**
     * Force-closes whatever is currently buffered, even if a header never followed - EOF in batch
     * mode (T18), or an idle flush-timeout/shutdown in follow mode (T22). Empty if nothing is buffered.
     */
    public Optional<LogEvent> flushPending() {
        if (buffer.isEmpty()) {
            return Optional.empty();
        }
        LogEvent event = buildEvent(currentHeader, buffer, currentStartLine, sequence++);
        buffer = new ArrayList<>();
        currentHeader = null;
        return Optional.of(event);
    }

    /** The physical line number the still-open buffer started at (only meaningful while {@link #hasPending()}). */
    public int pendingStartLine() {
        return currentStartLine;
    }

    private LogEvent buildEvent(HeaderFields header, List<String> rawLines, int lineNumber, long eventSequence) {
        List<String> textLines = toTextLines(header, rawLines);

        GroundTruth groundTruth = null;
        if (context.oracle() && !textLines.isEmpty()) {
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
            context.datasetId(),
            context.sourceFile(),
            lineNumber,
            rawLines.size(),
            eventSequence,
            header != null ? header.instant() : null,
            header != null ? header.timestampRaw() : null,
            context.service(),
            context.module(),
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
            context.code(),
            groundTruth,
            context.parserFormat());
    }

    /**
     * {@code lines[0]} is the header's own message ({@code null} header => the event is an orphan, so
     * every raw line is text); every other line is ANSI-stripped (defensively - real Docker logs never
     * carry ANSI codes, 0.9) before message/exception splitting.
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
     * The first line matching {@code StackTraceParser.CLASS_LINE} immediately followed by a frame, or
     * - when the frames start with no class line at all (T18 step 2, see {@code docs/log-format.md}
     * §4) - the first frame line itself. {@code -1} when the event has no stack trace.
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
