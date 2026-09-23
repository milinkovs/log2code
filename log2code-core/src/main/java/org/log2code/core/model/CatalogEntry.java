package org.log2code.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** One log statement in one code version (document {@code log2code-catalog}, {@code _id = statementId}). */
public record CatalogEntry(
    String statementId,
    String logicalId,
    CodeUnit codeUnit,
    String module,
    String service,
    String filePath,
    String fileId,
    @JsonProperty("package") String packageName,
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
    Level level,
    boolean levelDynamic,
    String templateRaw,
    String template,
    String templateKind,
    String unsupportedReason,
    String regex,
    List<String> constantTokens,
    int literalLength,
    int placeholderCount,
    boolean hasThrowableArg,
    EnclosingBlock enclosing,
    ControlContext control,
    String snippet,
    int snippetStartLine,
    String githubUrl,
    String analyzerVersion,
    Instant analyzedAt
) {

    /** A copy of this entry with {@code github_url} replaced (T15: filled in at write time). */
    public CatalogEntry withGithubUrl(String githubUrl) {
        return new CatalogEntry(statementId, logicalId, codeUnit, module, service, filePath, fileId, packageName,
            classFqn, classBinary, methodName, methodSignature, methodId, inLambda, line, endLine, column,
            methodStartLine, methodEndLine, loggingApi, detection, loggerExpr, loggerName, loggerNameKind, level,
            levelDynamic, templateRaw, template, templateKind, unsupportedReason, regex, constantTokens,
            literalLength, placeholderCount, hasThrowableArg, enclosing, control, snippet, snippetStartLine,
            githubUrl, analyzerVersion, analyzedAt);
    }
}
