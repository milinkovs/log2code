package org.log2code.api.dto;

import java.util.List;

/** Response of {@code GET /api/methods/{methodId}} (T24 step 1, mirrors {@link org.log2code.core.model.MethodInfo}). */
public record MethodDetailDto(
    String methodId,
    CodeUnitDto codeUnit,
    String module,
    String service,
    String fileId,
    String filePath,
    String classFqn,
    String classBinary,
    String methodName,
    String methodSignature,
    int startLine,
    int endLine,
    List<String> annotations,
    boolean hasLogStatements,
    List<CallEdgeDto> calls,
    List<CallerRefDto> calledBy,
    int callerCount
) {
}
