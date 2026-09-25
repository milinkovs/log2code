package org.log2code.api.service;

import java.util.List;
import java.util.Map;
import org.log2code.api.dto.CandidateDto;
import org.log2code.api.dto.CausedByDto;
import org.log2code.api.dto.CodeVersionDto;
import org.log2code.api.dto.ExceptionInfoDto;
import org.log2code.api.dto.GroundTruthDto;
import org.log2code.api.dto.LogDetail;
import org.log2code.api.dto.LogSummary;
import org.log2code.api.dto.MatchResultDto;
import org.log2code.api.dto.StackFrameDto;
import org.log2code.core.model.Candidate;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.StackFrame;

/** Maps core {@link EnrichedLog} (and its nested types) to API DTOs (0.14: core model is never exposed directly). */
public final class LogMapper {

    /** {@code message} is truncated to this many characters in {@link LogSummary} (T23 step 3). */
    public static final int SUMMARY_MESSAGE_MAX_LENGTH = 500;

    private LogMapper() {
    }

    public static LogSummary toSummary(EnrichedLog log) {
        MatchResult match = log.match();
        return new LogSummary(
            log.logId(),
            log.timestamp(),
            log.service(),
            log.level() == null ? null : log.level().name(),
            log.thread(),
            log.loggerRaw(),
            truncate(log.message(), SUMMARY_MESSAGE_MAX_LENGTH),
            match == null ? null : match.status(),
            match == null ? null : match.confidence(),
            match == null ? null : match.confidenceLevel(),
            log.exception() != null,
            log.traceId(),
            match == null ? null : match.classFqn(),
            match == null ? null : match.methodName(),
            match == null ? null : match.line()
        );
    }

    public static LogDetail toDetail(EnrichedLog log) {
        return new LogDetail(
            log.logId(),
            log.timestamp(),
            log.timestampRaw(),
            log.datasetId(),
            log.sourceFile(),
            log.lineNumber(),
            log.lineCount(),
            log.sequence(),
            log.service(),
            log.module(),
            log.appName(),
            log.pid(),
            log.thread(),
            log.level() == null ? null : log.level().name(),
            log.loggerRaw(),
            log.logger(),
            log.message(),
            log.raw(),
            log.traceId(),
            log.spanId(),
            toExceptionDto(log.exception()),
            toCodeVersionDto(log.code()),
            toMatchDto(log.match()),
            toGroundTruthDto(log.groundTruth()),
            log.parserFormat(),
            log.ingesterVersion(),
            log.ingestedAt()
        );
    }

    private static MatchResultDto toMatchDto(MatchResult match) {
        if (match == null) {
            return null;
        }
        List<CandidateDto> candidates = match.candidates() == null ? null
            : match.candidates().stream().map(LogMapper::toCandidateDto).toList();
        Map<String, Double> scoreBreakdown = match.scoreBreakdown() == null ? null : Map.copyOf(match.scoreBreakdown());
        return new MatchResultDto(
            match.status(), match.statementId(), match.confidence(), match.confidenceLevel(),
            candidates, scoreBreakdown, match.args(), match.codeUnit(), match.module(), match.classFqn(),
            match.methodName(), match.filePath(), match.loggingApi(), match.templateKind(), match.line(),
            match.template(), match.githubUrl());
    }

    private static CandidateDto toCandidateDto(Candidate candidate) {
        return new CandidateDto(candidate.statementId(), candidate.score());
    }

    private static ExceptionInfoDto toExceptionDto(ExceptionInfo exception) {
        if (exception == null) {
            return null;
        }
        List<StackFrameDto> frames = exception.frames() == null ? null
            : exception.frames().stream().map(LogMapper::toStackFrameDto).toList();
        List<CausedByDto> causedBy = exception.causedBy() == null ? null
            : exception.causedBy().stream().map(LogMapper::toCausedByDto).toList();
        return new ExceptionInfoDto(exception.className(), exception.rootClass(), exception.message(), frames, causedBy);
    }

    private static CausedByDto toCausedByDto(CausedBy causedBy) {
        List<StackFrameDto> frames = causedBy.frames() == null ? null
            : causedBy.frames().stream().map(LogMapper::toStackFrameDto).toList();
        return new CausedByDto(causedBy.className(), causedBy.message(), frames);
    }

    private static StackFrameDto toStackFrameDto(StackFrame frame) {
        return new StackFrameDto(frame.className(), frame.method(), frame.file(), frame.line(),
            frame.inProject(), frame.codeUnit(), frame.fileId(), frame.githubUrl());
    }

    private static CodeVersionDto toCodeVersionDto(CodeVersion code) {
        return code == null ? null : new CodeVersionDto(code.name(), code.version());
    }

    private static GroundTruthDto toGroundTruthDto(GroundTruth groundTruth) {
        return groundTruth == null ? null
            : new GroundTruthDto(groundTruth.className(), groundTruth.method(), groundTruth.line(), groundTruth.reliable());
    }

    private static String truncate(String message, int maxLength) {
        if (message == null || message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength);
    }
}
