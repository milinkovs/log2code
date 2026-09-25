package org.log2code.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response of {@code GET /api/catalog/{statementId}} (T24 step 1): a catalog entry with its
 * {@code control} context, deliberately without {@code regex} (mirrors
 * {@link org.log2code.core.model.CatalogEntry}, minus {@code regex}).
 */
public record CatalogEntryDto(
    String statementId,
    String logicalId,
    CodeUnitDto codeUnit,
    String module,
    String service,
    String filePath,
    String fileId,
    String packageName,
    String classFqn,
    String classBinary,
    String methodName,
    String methodSignature,
    String methodId,
    boolean inLambda,
    int line,
    int endLine,
    int column,
    int methodStartLine,
    int methodEndLine,
    String loggingApi,
    String detection,
    String loggerExpr,
    String loggerName,
    String loggerNameKind,
    String level,
    boolean levelDynamic,
    String templateRaw,
    String template,
    String templateKind,
    String unsupportedReason,
    List<String> constantTokens,
    int literalLength,
    int placeholderCount,
    boolean hasThrowableArg,
    EnclosingBlockDto enclosing,
    ControlContextDto control,
    String snippet,
    int snippetStartLine,
    String githubUrl,
    String analyzerVersion,
    Instant analyzedAt
) {
}
