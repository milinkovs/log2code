package org.log2code.core.model;

import java.util.List;

/** One project method (document {@code log2code-methods}, {@code _id = methodId}). */
public record MethodInfo(
    String methodId,
    CodeUnit codeUnit,
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
    List<CallEdge> calls,
    List<CallerRef> calledBy,
    int callerCount
) {
}
