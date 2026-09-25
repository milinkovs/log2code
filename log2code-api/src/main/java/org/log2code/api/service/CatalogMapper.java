package org.log2code.api.service;

import java.util.List;
import org.log2code.api.dto.CallSiteDto;
import org.log2code.api.dto.CatalogEntryDto;
import org.log2code.api.dto.CodeUnitDto;
import org.log2code.api.dto.ConditionDto;
import org.log2code.api.dto.ControlContextDto;
import org.log2code.api.dto.EarlyExitDto;
import org.log2code.api.dto.EnclosingBlockDto;
import org.log2code.api.dto.PrecedingStatementDto;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EarlyExit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.PrecedingStatement;

/** Maps core {@link CatalogEntry} to {@link CatalogEntryDto} (T24 step 1: deliberately drops {@code regex}). */
public final class CatalogMapper {

    private CatalogMapper() {
    }

    public static CatalogEntryDto toDto(CatalogEntry entry) {
        return new CatalogEntryDto(
            entry.statementId(),
            entry.logicalId(),
            toCodeUnitDto(entry.codeUnit()),
            entry.module(),
            entry.service(),
            entry.filePath(),
            entry.fileId(),
            entry.packageName(),
            entry.classFqn(),
            entry.classBinary(),
            entry.methodName(),
            entry.methodSignature(),
            entry.methodId(),
            entry.inLambda(),
            entry.line(),
            entry.endLine(),
            entry.column(),
            entry.methodStartLine(),
            entry.methodEndLine(),
            entry.loggingApi(),
            entry.detection(),
            entry.loggerExpr(),
            entry.loggerName(),
            entry.loggerNameKind(),
            entry.level() == null ? null : entry.level().name(),
            entry.levelDynamic(),
            entry.templateRaw(),
            entry.template(),
            entry.templateKind(),
            entry.unsupportedReason(),
            entry.constantTokens(),
            entry.literalLength(),
            entry.placeholderCount(),
            entry.hasThrowableArg(),
            toEnclosingDto(entry.enclosing()),
            toControlDto(entry.control()),
            entry.snippet(),
            entry.snippetStartLine(),
            entry.githubUrl(),
            entry.analyzerVersion(),
            entry.analyzedAt()
        );
    }

    public static CodeUnitDto toCodeUnitDto(CodeUnit codeUnit) {
        return codeUnit == null ? null : new CodeUnitDto(codeUnit.type(), codeUnit.name(), codeUnit.version());
    }

    private static EnclosingBlockDto toEnclosingDto(EnclosingBlock enclosing) {
        return enclosing == null ? null
            : new EnclosingBlockDto(enclosing.blockKind(), enclosing.condition(), enclosing.branch(), enclosing.startLine(), enclosing.endLine());
    }

    private static ControlContextDto toControlDto(ControlContext control) {
        if (control == null) {
            return null;
        }
        List<ConditionDto> conditions = control.conditions() == null ? null
            : control.conditions().stream().map(CatalogMapper::toConditionDto).toList();
        List<EarlyExitDto> earlyExits = control.earlyExits() == null ? null
            : control.earlyExits().stream().map(CatalogMapper::toEarlyExitDto).toList();
        List<PrecedingStatementDto> preceding = control.preceding() == null ? null
            : control.preceding().stream().map(CatalogMapper::toPrecedingDto).toList();
        List<CallSiteDto> callsBefore = control.callsBefore() == null ? null
            : control.callsBefore().stream().map(CatalogMapper::toCallSiteDto).toList();
        return new ControlContextDto(conditions, earlyExits, preceding, callsBefore);
    }

    private static ConditionDto toConditionDto(Condition condition) {
        return new ConditionDto(condition.kind(), condition.text(), condition.line(), condition.negated());
    }

    private static EarlyExitDto toEarlyExitDto(EarlyExit earlyExit) {
        return new EarlyExitDto(earlyExit.text(), earlyExit.line(), earlyExit.exitKind());
    }

    private static PrecedingStatementDto toPrecedingDto(PrecedingStatement preceding) {
        return new PrecedingStatementDto(preceding.kind(), preceding.text(), preceding.line());
    }

    private static CallSiteDto toCallSiteDto(CallSite callSite) {
        return new CallSiteDto(callSite.line(), callSite.text(), callSite.target(), callSite.targetMethodId(), callSite.resolved());
    }
}
