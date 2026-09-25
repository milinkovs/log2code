package org.log2code.api.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.log2code.api.dto.CandidateDto;
import org.log2code.api.dto.CatalogEntryDto;
import org.log2code.api.dto.ContextBundleDto;
import org.log2code.api.dto.ContextCallerDto;
import org.log2code.api.dto.ContextCausedByDto;
import org.log2code.api.dto.ContextCodeDto;
import org.log2code.api.dto.ContextExceptionDto;
import org.log2code.api.dto.ContextMatchDto;
import org.log2code.api.dto.ContextNeighborsDto;
import org.log2code.api.dto.ContextStackFrameDto;
import org.log2code.api.dto.ControlContextDto;
import org.log2code.api.dto.NeighborsResponse;
import org.log2code.api.dto.TraceResponse;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.SourceFile;
import org.log2code.core.model.StackFrame;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;

/**
 * Backs {@code GET /api/logs/{logId}/context} (T25 step 1): assembles the whole context bundle by
 * re-using the same data every other endpoint in this module already serves (catalog, sources,
 * methods, neighbors, trace), plus two pieces of on-the-fly enrichment this endpoint alone needs:
 * the winning statement's full {@code method_source}, and a ±{@value #SNIPPET_RADIUS}-line
 * snippet for each project caller / project exception frame. {@code docs/context-bundle.md} has
 * the full schema.
 */
public final class ContextBundleService {

    /** Default {@code neighbors} query parameter (symmetric before/after count) when omitted. */
    public static final int DEFAULT_NEIGHBORS = 10;
    /** "callers: jedan nivo, najviše 10" (T25 step 1). */
    static final int MAX_CALLERS = 10;
    /** "trace: najviše 50" (T25 step 1) — deliberately smaller than the standalone endpoint's default of 200. */
    static final int MAX_TRACE = 50;
    /** Exception frames get "snippet ±3 linije" (T25 step 1); callers reuse the same radius for consistency. */
    static final int SNIPPET_RADIUS = 3;

    private final DocumentReader documentReader;
    private final IndexNames indexNames;
    private final LogNeighborhoodService neighborhoodService;

    public ContextBundleService(DocumentReader documentReader, IndexNames indexNames, LogNeighborhoodService neighborhoodService) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
        this.neighborhoodService = neighborhoodService;
    }

    public ContextBundleDto build(EnrichedLog log, int neighbors) {
        MatchResult match = log.match();
        CatalogEntry entry = fetchStatement(match);

        CatalogEntryDto statementDto = entry == null ? null : CatalogMapper.toDto(entry);
        ControlContextDto controlDto = statementDto == null ? null : statementDto.control();

        NeighborsResponse rawNeighbors = neighborhoodService.neighbors(log, neighbors, neighbors, null);
        TraceResponse trace = neighborhoodService.trace(log, MAX_TRACE);

        return new ContextBundleDto(
            ContextBundleDto.SCHEMA_VERSION,
            LogMapper.toDetail(log),
            toMatchDto(match),
            statementDto,
            entry == null ? null : buildCode(entry),
            controlDto,
            entry == null ? List.of() : buildCallers(entry),
            buildException(log.exception()),
            new ContextNeighborsDto(rawNeighbors.before(), rawNeighbors.after()),
            trace
        );
    }

    private CatalogEntry fetchStatement(MatchResult match) {
        if (match == null || match.statementId() == null) {
            return null;
        }
        return fetchCatalogEntry(match.statementId());
    }

    private static ContextMatchDto toMatchDto(MatchResult match) {
        if (match == null) {
            return null;
        }
        List<CandidateDto> candidates = match.candidates() == null ? List.of()
            : match.candidates().stream().map(c -> new CandidateDto(c.statementId(), c.score())).toList();
        return new ContextMatchDto(match.status(), match.confidence(), match.confidenceLevel(), candidates);
    }

    private ContextCodeDto buildCode(CatalogEntry entry) {
        SourceFile source = entry.fileId() == null ? null : fetchSource(entry.fileId());
        String methodSource = source == null ? null : extractRange(source.content(), entry.methodStartLine(), entry.methodEndLine());
        return new ContextCodeDto(entry.filePath(), entry.githubUrl(), methodSource, entry.methodStartLine(),
            entry.snippet(), entry.snippetStartLine());
    }

    private List<ContextCallerDto> buildCallers(CatalogEntry entry) {
        if (entry.methodId() == null) {
            return List.of();
        }
        MethodInfo method = fetchMethod(entry.methodId());
        if (method == null || method.calledBy() == null) {
            return List.of();
        }
        return method.calledBy().stream().limit(MAX_CALLERS).map(this::toCallerDto).toList();
    }

    private ContextCallerDto toCallerDto(CallerRef ref) {
        MethodInfo caller = fetchMethod(ref.methodId());
        String filePath = caller == null ? null : caller.filePath();
        String fileId = caller == null ? null : caller.fileId();
        return new ContextCallerDto(ref.classFqn(), ref.methodName(), filePath, ref.line(), snippetAround(fileId, ref.line()));
    }

    private ContextExceptionDto buildException(ExceptionInfo exception) {
        if (exception == null) {
            return null;
        }
        List<ContextStackFrameDto> frames = exception.frames() == null ? List.of()
            : exception.frames().stream().map(this::resolveFrame).toList();
        List<ContextCausedByDto> causedBy = exception.causedBy() == null ? List.of()
            : exception.causedBy().stream().map(this::resolveCausedBy).toList();
        return new ContextExceptionDto(exception.className(), exception.rootClass(), exception.message(), frames, causedBy);
    }

    private ContextCausedByDto resolveCausedBy(CausedBy causedBy) {
        List<ContextStackFrameDto> frames = causedBy.frames() == null ? List.of()
            : causedBy.frames().stream().map(this::resolveFrame).toList();
        return new ContextCausedByDto(causedBy.className(), causedBy.message(), frames);
    }

    private ContextStackFrameDto resolveFrame(StackFrame frame) {
        String snippet = frame.inProject() ? snippetAround(frame.fileId(), frame.line()) : null;
        return new ContextStackFrameDto(frame.className(), frame.method(), frame.file(), frame.line(),
            frame.inProject(), frame.codeUnit(), frame.fileId(), frame.githubUrl(), snippet);
    }

    /** Best-effort: {@code null} (never throws) when the file id is unknown or the source doc is missing. */
    private String snippetAround(String fileId, Integer line) {
        if (fileId == null || line == null) {
            return null;
        }
        SourceFile source = fetchSource(fileId);
        return source == null ? null : extractSnippet(source.content(), line, SNIPPET_RADIUS);
    }

    private CatalogEntry fetchCatalogEntry(String statementId) {
        try {
            return documentReader.get(indexNames.catalog(), statementId, CatalogEntry.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SourceFile fetchSource(String fileId) {
        try {
            return documentReader.get(indexNames.sources(), fileId, SourceFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private MethodInfo fetchMethod(String methodId) {
        try {
            return documentReader.get(indexNames.methods(), methodId, MethodInfo.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String extractRange(String content, int startLine, int endLine) {
        if (content == null || startLine <= 0 || endLine < startLine) {
            return null;
        }
        List<String> lines = content.lines().toList();
        int from = Math.max(1, startLine);
        int to = Math.min(lines.size(), endLine);
        if (from > to) {
            return null;
        }
        return String.join("\n", lines.subList(from - 1, to));
    }

    static String extractSnippet(String content, int line, int radius) {
        if (content == null || line <= 0) {
            return null;
        }
        List<String> lines = content.lines().toList();
        if (line > lines.size()) {
            return null;
        }
        int from = Math.max(1, line - radius);
        int to = Math.min(lines.size(), line + radius);
        return String.join("\n", lines.subList(from - 1, to));
    }
}
