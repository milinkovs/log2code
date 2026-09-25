package org.log2code.api.service;

import java.util.List;
import org.log2code.api.dto.CallEdgeDto;
import org.log2code.api.dto.CallerRefDto;
import org.log2code.api.dto.MethodDetailDto;
import org.log2code.core.model.CallEdge;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.MethodInfo;

/** Maps core {@link MethodInfo} to {@link MethodDetailDto} (T24 step 1). */
public final class MethodMapper {

    private MethodMapper() {
    }

    public static MethodDetailDto toDetailDto(MethodInfo info) {
        List<CallEdgeDto> calls = info.calls() == null ? List.of() : info.calls().stream().map(MethodMapper::toCallEdgeDto).toList();
        List<CallerRefDto> calledBy = info.calledBy() == null ? List.of() : info.calledBy().stream().map(MethodMapper::toCallerRefDto).toList();
        return new MethodDetailDto(
            info.methodId(),
            CatalogMapper.toCodeUnitDto(info.codeUnit()),
            info.module(),
            info.service(),
            info.fileId(),
            info.filePath(),
            info.classFqn(),
            info.classBinary(),
            info.methodName(),
            info.methodSignature(),
            info.startLine(),
            info.endLine(),
            info.annotations() == null ? List.of() : info.annotations(),
            info.hasLogStatements(),
            calls,
            calledBy,
            info.callerCount()
        );
    }

    private static CallEdgeDto toCallEdgeDto(CallEdge edge) {
        return new CallEdgeDto(edge.line(), edge.text(), edge.targetMethodId(), edge.targetFqn(), edge.resolved(), edge.viaInterface());
    }

    private static CallerRefDto toCallerRefDto(CallerRef ref) {
        return new CallerRefDto(ref.methodId(), ref.classFqn(), ref.methodName(), ref.fileId(), ref.line());
    }
}
