package org.log2code.api.llm.explain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.llm.explain.ExplainContext.CallerBlock;
import org.log2code.api.llm.explain.ExplainContext.CodeBlock;
import org.log2code.api.llm.explain.ExplainContext.FlowPart;
import org.log2code.api.llm.explain.ExplainContext.LogPart;
import org.log2code.api.llm.explain.ExplainContext.NeighborLine;
import org.log2code.api.llm.explain.ExplainContext.StackCode;
import org.log2code.api.llm.explain.ExplainContext.StatementPart;
import org.log2code.api.service.LogNeighborhoodService;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EarlyExit;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.PrecedingStatement;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.StackFrame;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;

/**
 * Collects the parts of the "Explain" prompt for one log and one {@link ExplainLevel} (10.1, T41):
 * reads the catalog entry, sources and methods through {@link DocumentReader}, and the neighbors through
 * {@link LogNeighborhoodService}. Only what the level needs is read. Parts that do not exist are left out,
 * and the reason is recorded in {@link ExplainContext#sections()}.
 */
public final class ExplainContextBuilder {

    static final int MAX_STACK_TRACE_LINES = 60;
    static final int MAX_METHOD_LINES = 150;
    static final int METHOD_WINDOW_RADIUS = 60;
    static final int MAX_STACK_FRAMES = 10;
    static final int FRAME_RADIUS = 3;
    static final int MAX_CALLERS_PER_LEVEL = 5;
    static final int MAX_CALLER_METHODS = 12;
    static final int MAX_CALLER_METHOD_LINES = 80;
    static final int CALLER_WINDOW_RADIUS = 40;
    static final int L3_CALLER_DEPTH = 1;
    static final int L4_CALLER_DEPTH = 3;
    static final int NEIGHBORS_PER_SIDE = 5;
    static final int MAX_NEIGHBOR_MESSAGE_CHARS = 300;
    static final int MAX_FLOW_TEXT_CHARS = 200;
    static final int SHORT_SHA_LENGTH = 7;

    private static final DateTimeFormatter NEIGHBOR_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_CLOCK_OFFSET_SECONDS = 18 * 3600;

    private final DocumentReader documentReader;
    private final IndexNames indexNames;
    private final LogNeighborhoodService neighborhoodService;

    public ExplainContextBuilder(DocumentReader documentReader, IndexNames indexNames, LogNeighborhoodService neighborhoodService) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
        this.neighborhoodService = neighborhoodService;
    }

    public ExplainContext build(EnrichedLog log, ExplainLevel level) {
        Docs docs = new Docs();
        Sections sections = new Sections();

        sections.include(ExplainSection.LOG);
        String stackTrace = stackTrace(log);
        sections.set(ExplainSection.EXCEPTION, stackTrace != null, ExplainSection.REASON_NO_EXCEPTION);

        CatalogEntry entry = null;
        String statementReason = null;
        if (level.atLeast(ExplainLevel.L1)) {
            statementReason = statementReason(log.match());
            if (statementReason == null) {
                entry = docs.catalog(log.match().statementId());
                if (entry == null) {
                    statementReason = ExplainSection.REASON_UNAVAILABLE;
                }
            }
        }

        StatementPart statement = null;
        CodeBlock method = null;
        if (level.atLeast(ExplainLevel.L1)) {
            if (entry == null) {
                sections.omit(ExplainSection.STATEMENT, statementReason);
                sections.omit(ExplainSection.METHOD, statementReason);
            } else {
                statement = statementPart(log.match(), entry);
                sections.include(ExplainSection.STATEMENT);
                method = methodCode(entry, docs);
                sections.set(ExplainSection.METHOD, method != null, ExplainSection.REASON_UNAVAILABLE);
            }
        }

        FlowPart flow = null;
        List<StackCode> stackCode = List.of();
        if (level.atLeast(ExplainLevel.L2)) {
            if (entry == null) {
                sections.omit(ExplainSection.FLOW, statementReason);
            } else {
                flow = flowPart(entry.control());
                sections.set(ExplainSection.FLOW, flow != null, ExplainSection.REASON_NO_CONTROL);
            }
            stackCode = stackCode(log.exception(), docs, sections);
        }

        List<CallerBlock> callers = List.of();
        if (level.atLeast(ExplainLevel.L3)) {
            int depth = level.atLeast(ExplainLevel.L4) ? L4_CALLER_DEPTH : L3_CALLER_DEPTH;
            callers = callers(entry, statementReason, depth, docs, sections);
        }

        List<NeighborLine> neighbors = List.of();
        if (level.atLeast(ExplainLevel.L4)) {
            neighbors = neighbors(log);
            sections.set(ExplainSection.NEIGHBORS, neighbors.size() > 1, ExplainSection.REASON_NO_NEIGHBORS);
            if (neighbors.size() <= 1) {
                neighbors = List.of();
            }
        }

        return new ExplainContext(level, logPart(log), stackTrace, statement, method, flow, stackCode, callers, neighbors,
            sections.toList());
    }

    // ---- L0 ----

    private static LogPart logPart(EnrichedLog log) {
        return new LogPart(log.service(), log.timestampRaw(), log.level() == null ? null : log.level().name(),
            log.logger() != null ? log.logger() : log.loggerRaw(), log.thread(), blankToNull(log.traceId()), log.message());
    }

    /**
     * The exception text from {@code raw}, without the line(s) that carry the log message: it starts at the
     * first line after the first one that begins with the exception class, or (when none does) at the second
     * line. {@code null} when the log has no exception or {@code raw} has no such text.
     */
    static String stackTrace(EnrichedLog log) {
        if (log.exception() == null || log.raw() == null) {
            return null;
        }
        List<String> lines = log.raw().lines().toList();
        int start = 1;
        String className = log.exception().className();
        if (className != null) {
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).startsWith(className)) {
                    start = i;
                    break;
                }
            }
        }
        int end = lines.size();
        while (end > start && lines.get(end - 1).isBlank()) {
            end--;
        }
        if (start >= end) {
            return null;
        }
        List<String> trace = lines.subList(start, end);
        if (trace.size() <= MAX_STACK_TRACE_LINES) {
            return String.join("\n", trace);
        }
        return String.join("\n", trace.subList(0, MAX_STACK_TRACE_LINES))
            + "\n… (još " + (trace.size() - MAX_STACK_TRACE_LINES) + " linija)";
    }

    // ---- L1 ----

    /** {@code null} when the log is linked to a statement, otherwise {@code unmatched}. */
    private static String statementReason(MatchResult match) {
        boolean linked = match != null && match.statementId() != null
            && !MatchResult.STATUS_UNMATCHED.equals(match.status());
        return linked ? null : ExplainSection.REASON_UNMATCHED;
    }

    private static StatementPart statementPart(MatchResult match, CatalogEntry entry) {
        return new StatementPart(match.status(), match.confidenceLevel(), match.confidence(), codeUnitText(entry.codeUnit()),
            entry.filePath(), entry.line(), entry.classFqn() + "#" + entry.methodSignature(), entry.template());
    }

    private static String codeUnitText(CodeUnit unit) {
        if (unit == null) {
            return null;
        }
        if (CodeUnit.TYPE_PROJECT.equals(unit.type())) {
            String version = unit.version() == null ? "" : unit.version();
            return "projekat " + unit.name() + " @ " + version.substring(0, Math.min(SHORT_SHA_LENGTH, version.length()));
        }
        return "biblioteka " + unit.name() + ":" + unit.version();
    }

    private CodeBlock methodCode(CatalogEntry entry, Docs docs) {
        SourceFile source = docs.source(entry.fileId());
        if (source == null) {
            return null;
        }
        return window(source.content(), entry.methodStartLine(), entry.methodEndLine(), entry.line(),
            MAX_METHOD_LINES, METHOD_WINDOW_RADIUS);
    }

    /**
     * Lines {@code start..end} of {@code content}; when that is more than {@code maxLines}, only {@code radius}
     * lines either side of {@code focus} (kept inside the method), with {@code cutBefore}/{@code cutAfter} set.
     * {@code null} when the range is not in the file.
     */
    static CodeBlock window(String content, int start, int end, int focus, int maxLines, int radius) {
        if (content == null || start <= 0 || end < start) {
            return null;
        }
        List<String> lines = content.lines().toList();
        int from = start;
        int to = Math.min(lines.size(), end);
        if (from > to) {
            return null;
        }
        if (to - from + 1 <= maxLines) {
            return new CodeBlock(from, List.copyOf(lines.subList(from - 1, to)), false, false);
        }
        int center = Math.max(from, Math.min(to, focus));
        int windowFrom = Math.max(from, center - radius);
        int windowTo = Math.min(to, center + radius);
        return new CodeBlock(windowFrom, List.copyOf(lines.subList(windowFrom - 1, windowTo)), windowFrom > from, windowTo < to);
    }

    // ---- L2 ----

    private static FlowPart flowPart(ControlContext control) {
        if (control == null) {
            return null;
        }
        List<String> inside = nullSafe(control.conditions()).stream()
            .map(c -> conditionText(c) + " (linija " + c.line() + ")").toList();
        List<String> exits = nullSafe(control.earlyExits()).stream()
            .map(ExplainContextBuilder::earlyExitText).toList();
        List<String> preceding = nullSafe(control.preceding()).stream()
            .map(ExplainContextBuilder::precedingText).toList();
        List<String> calls = nullSafe(control.callsBefore()).stream()
            .map(c -> oneLine(c.text(), MAX_FLOW_TEXT_CHARS) + " (linija " + c.line() + ")").toList();
        FlowPart flow = new FlowPart(inside, exits, preceding, calls);
        return flow.isEmpty() ? null : flow;
    }

    static String conditionText(Condition condition) {
        String text = oneLine(condition.text(), MAX_FLOW_TEXT_CHARS);
        return switch (condition.kind()) {
            case "if" -> "if (" + text + ")";
            case "else" -> "NE: " + text;
            case "catch" -> "catch (" + text + ")";
            case "switch_case" -> text.isEmpty() ? "case" : "case " + text;
            default -> text.isEmpty() ? condition.kind() : condition.kind() + " (" + text + ")";
        };
    }

    private static String earlyExitText(EarlyExit exit) {
        return oneLine(exit.text(), MAX_FLOW_TEXT_CHARS) + " (linija " + exit.line() + ", " + exit.exitKind() + ")";
    }

    private static String precedingText(PrecedingStatement statement) {
        return oneLine(statement.text(), MAX_FLOW_TEXT_CHARS) + " (linija " + statement.line() + ")";
    }

    /** ±{@value #FRAME_RADIUS} lines around every project frame (main exception first, then each cause); one block per file and line. */
    private List<StackCode> stackCode(ExceptionInfo exception, Docs docs, Sections sections) {
        if (exception == null) {
            sections.omit(ExplainSection.STACK_CODE, ExplainSection.REASON_NO_EXCEPTION);
            return List.of();
        }
        Stream<StackFrame> causedByFrames = nullSafe(exception.causedBy()).stream().flatMap(c -> nullSafe(c.frames()).stream());
        List<StackFrame> projectFrames = Stream.concat(nullSafe(exception.frames()).stream(), causedByFrames)
            .filter(f -> f.inProject() && f.fileId() != null && f.line() != null).toList();
        if (projectFrames.isEmpty()) {
            sections.omit(ExplainSection.STACK_CODE, ExplainSection.REASON_NO_PROJECT_FRAMES);
            return List.of();
        }

        List<StackCode> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StackFrame frame : projectFrames) {
            if (result.size() >= MAX_STACK_FRAMES) {
                break;
            }
            if (!seen.add(frame.fileId() + ":" + frame.line())) {
                continue;
            }
            SourceFile source = docs.source(frame.fileId());
            CodeBlock code = source == null ? null : window(source.content(),
                Math.max(1, frame.line() - FRAME_RADIUS), frame.line() + FRAME_RADIUS, frame.line(), Integer.MAX_VALUE, 0);
            if (code != null && frame.line() <= code.firstLine() + code.lines().size() - 1) {
                result.add(new StackCode(simpleName(frame.className()) + "." + frame.method() + " — "
                    + (frame.file() != null ? frame.file() : source.filePath()) + ":" + frame.line(), code));
            }
        }
        sections.set(ExplainSection.STACK_CODE, !result.isEmpty(), ExplainSection.REASON_UNAVAILABLE);
        return List.copyOf(result);
    }

    // ---- L3 / L4 ----

    /**
     * Breadth-first search over {@code called_by} from the statement's method. Level 1 is the callers of that
     * method; level n is the union of the callers of the methods kept on level n-1. A method appears once
     * (by {@code method_id}), a level is ordered by class, method and line and holds at most
     * {@value #MAX_CALLERS_PER_LEVEL} methods, and all levels together at most {@value #MAX_CALLER_METHODS}.
     */
    private List<CallerBlock> callers(CatalogEntry entry, String statementReason, int maxDepth, Docs docs, Sections sections) {
        if (entry == null) {
            sections.omit(ExplainSection.CALLERS, statementReason);
            return List.of();
        }
        if (CodeUnit.TYPE_DEPENDENCY.equals(entry.codeUnit() == null ? null : entry.codeUnit().type())) {
            sections.omit(ExplainSection.CALLERS, ExplainSection.REASON_LIBRARY);
            return List.of();
        }
        MethodInfo start = entry.methodId() == null ? null : docs.method(entry.methodId());
        if (start == null) {
            sections.omit(ExplainSection.CALLERS, ExplainSection.REASON_UNAVAILABLE);
            return List.of();
        }

        List<CallerBlock> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(start.methodId());
        List<MethodInfo> frontier = List.of(start);
        for (int depth = 1; depth <= maxDepth && result.size() < MAX_CALLER_METHODS && !frontier.isEmpty(); depth++) {
            List<CallerRef> refs = frontier.stream().flatMap(m -> nullSafe(m.calledBy()).stream())
                .sorted(Comparator.comparing((CallerRef r) -> nullToEmpty(r.classFqn()))
                    .thenComparing(r -> nullToEmpty(r.methodName())).thenComparingInt(CallerRef::line))
                .toList();
            List<MethodInfo> kept = new ArrayList<>();
            for (CallerRef ref : refs) {
                if (kept.size() >= MAX_CALLERS_PER_LEVEL || result.size() >= MAX_CALLER_METHODS) {
                    break;
                }
                if (ref.methodId() == null || !visited.add(ref.methodId())) {
                    continue;
                }
                MethodInfo caller = docs.method(ref.methodId());
                result.add(callerBlock(depth, ref, caller, docs));
                kept.add(caller);
            }
            frontier = kept.stream().filter(Objects::nonNull).toList();
        }
        sections.set(ExplainSection.CALLERS, !result.isEmpty(), ExplainSection.REASON_NO_CALLERS);
        return List.copyOf(result);
    }

    private static CallerBlock callerBlock(int depth, CallerRef ref, MethodInfo caller, Docs docs) {
        if (caller == null) {
            return new CallerBlock(depth, ref.classFqn() + "#" + ref.methodName() + ", poziv u liniji " + ref.line(), null);
        }
        StringBuilder title = new StringBuilder(caller.classFqn()).append('#').append(caller.methodSignature())
            .append(" — ").append(caller.filePath()).append(", poziv u liniji ").append(ref.line());
        List<String> rest = nullSafe(caller.annotations()).stream().filter(a -> a.endsWith("Mapping")).map(a -> "@" + a).toList();
        if (!rest.isEmpty()) {
            title.append(", REST ulaz (").append(String.join(", ", rest)).append(')');
        }
        SourceFile source = caller.fileId() == null ? null : docs.source(caller.fileId());
        CodeBlock code = source == null ? null : window(source.content(), caller.startLine(), caller.endLine(), ref.line(),
            MAX_CALLER_METHOD_LINES, CALLER_WINDOW_RADIUS);
        return new CallerBlock(depth, title.toString(), code);
    }

    /** {@value #NEIGHBORS_PER_SIDE} logs before and after, from the same file (service); the log itself is marked. */
    private List<NeighborLine> neighbors(EnrichedLog log) {
        NeighborsResponse response = neighborhoodService.neighbors(log, NEIGHBORS_PER_SIDE, NEIGHBORS_PER_SIDE,
            LogNeighborhoodService.SCOPE_SERVICE);
        ZoneOffset clock = clockOffset(log.timestampRaw(), log.timestamp());
        List<NeighborLine> lines = new ArrayList<>();
        response.before().forEach(s -> lines.add(new NeighborLine(neighborText(s, clock), false)));
        lines.add(new NeighborLine(neighborText(response.current(), clock), true));
        response.after().forEach(s -> lines.add(new NeighborLine(neighborText(s, clock), false)));
        return List.copyOf(lines);
    }

    /**
     * {@code HH:mm:ss.SSS LEVEL logger — message}, the time on the same clock as the log's own {@code timestamp_raw}
     * (see {@link #clockOffset}).
     */
    static String neighborText(LogSummary summary, ZoneOffset clock) {
        String time = summary.timestamp() == null ? "??:??:??.???" : NEIGHBOR_TIME.withZone(clock).format(summary.timestamp());
        return time + " " + nullToEmpty(summary.level()) + " " + nullToEmpty(summary.loggerRaw()) + " — "
            + oneLine(summary.message(), MAX_NEIGHBOR_MESSAGE_CHARS);
    }

    /**
     * The clock that {@code timestamp_raw} was written in, so the neighbor times read like the header's {@code Vreme}:
     * the offset it carries (`Z`, `+02:00`), or - for a raw time without a zone (a Logback pattern with a configured
     * timezone) - the difference between that local time and the parsed instant. UTC when neither can be determined.
     */
    static ZoneOffset clockOffset(String timestampRaw, Instant instant) {
        if (timestampRaw == null || timestampRaw.isBlank()) {
            return ZoneOffset.UTC;
        }
        String raw = timestampRaw.trim();
        try {
            return OffsetDateTime.parse(raw).getOffset();
        } catch (DateTimeParseException e) {
            // no offset in the text: fall through to the local-time reading
        }
        if (instant == null) {
            return ZoneOffset.UTC;
        }
        try {
            LocalDateTime local = LocalDateTime.parse(raw.replace(' ', 'T').replace(',', '.'));
            long seconds = local.toEpochSecond(ZoneOffset.UTC) - instant.getEpochSecond();
            boolean plausible = Math.abs(seconds) <= MAX_CLOCK_OFFSET_SECONDS && seconds % 60 == 0;
            return plausible ? ZoneOffset.ofTotalSeconds((int) seconds) : ZoneOffset.UTC;
        } catch (DateTimeParseException e) {
            return ZoneOffset.UTC;
        }
    }

    // ---- helpers ----

    static String oneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars - 1) + "…";
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "";
        }
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Documents read while building one context; a file that several frames or callers share is read once. */
    private final class Docs {
        private final Map<String, SourceFile> sources = new HashMap<>();
        private final Map<String, MethodInfo> methods = new HashMap<>();

        CatalogEntry catalog(String statementId) {
            return read(indexNames.catalog(), statementId, CatalogEntry.class);
        }

        SourceFile source(String fileId) {
            return fileId == null ? null : sources.computeIfAbsent(fileId, id -> read(indexNames.sources(), id, SourceFile.class));
        }

        MethodInfo method(String methodId) {
            return methodId == null ? null : methods.computeIfAbsent(methodId, id -> read(indexNames.methods(), id, MethodInfo.class));
        }

        private <T> T read(String index, String id, Class<T> type) {
            try {
                return documentReader.get(index, id, type);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** All eight sections in prompt order; everything starts as "not at this level". */
    private static final class Sections {
        private final Map<String, ExplainSection> byId = new LinkedHashMap<>();

        Sections() {
            ExplainSection.ALL_IDS.forEach(id -> byId.put(id, ExplainSection.omitted(id, ExplainSection.REASON_LEVEL)));
        }

        void include(String id) {
            byId.put(id, ExplainSection.included(id));
        }

        void omit(String id, String reason) {
            byId.put(id, ExplainSection.omitted(id, reason));
        }

        void set(String id, boolean included, String reasonIfOmitted) {
            byId.put(id, included ? ExplainSection.included(id) : ExplainSection.omitted(id, reasonIfOmitted));
        }

        List<ExplainSection> toList() {
            return List.copyOf(byId.values());
        }
    }
}
