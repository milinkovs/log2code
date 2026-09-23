package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.Node;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.template.ExtractedTemplate;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EnclosingBlock;

/**
 * Builds one {@link CatalogEntry} (0.7) from one {@link LogCall} plus everything T09 (message template)
 * and this package (class/method/block/control context) worked out for it. Pure: no I/O beyond the
 * already-read {@code fileLines} passed in for the snippet.
 */
public final class CatalogEntryBuilder {

    private CatalogEntryBuilder() {
    }

    public static CatalogEntry build(
        LogCall call,
        ExtractedTemplate extracted,
        Map<Node, Integer> anonymousClassNumbers,
        FileInfo file,
        CodeUnit codeUnit,
        int ordinal,
        List<String> fileLines,
        int snippetLines,
        int maxPrecedingStatements,
        String analyzerVersion,
        Instant analyzedAt
    ) {
        Node node = call.node();
        ClassContext classContext = ClassContextResolver.resolve(node, anonymousClassNumbers);
        MethodContext methodContext = MethodContextResolver.resolve(node);
        EnclosingBlock enclosing = EnclosingBlockResolver.resolve(node, methodContext);
        ControlContext control = ControlContextExtractor.extract(node, methodContext, maxPrecedingStatements);

        String template = extracted.template() != null ? extracted.template().toNormalized() : null;
        String regex = extracted.template() != null ? extracted.template().toRegex() : null;
        List<String> constantTokens = extracted.template() != null ? extracted.template().constantTokens() : List.of();
        int literalLength = extracted.template() != null ? extracted.template().literalLength() : 0;

        String statementId = StableIds.statementId(codeUnit.name(), codeUnit.version(), file.filePath(),
            classContext.classFqn(), methodContext.methodSignature(), template, ordinal);
        String logicalId = StableIds.logicalId(codeUnit.name(), file.filePath(),
            classContext.classFqn(), methodContext.methodSignature(), template, ordinal);
        String fileId = StableIds.fileId(codeUnit.name(), codeUnit.version(), file.filePath());

        int line = AstLines.startLine(node);
        int endLine = AstLines.endLine(node);
        int column = AstLines.startColumn(node);

        int snippetStart = Math.max(1, line - snippetLines);
        int snippetEnd = Math.min(fileLines.size(), endLine + snippetLines);
        String snippet = String.join("\n", fileLines.subList(snippetStart - 1, snippetEnd));

        return new CatalogEntry(
            statementId,
            logicalId,
            codeUnit,
            file.module(),
            file.service(),
            file.filePath(),
            fileId,
            file.packageName(),
            classContext.classFqn(),
            classContext.classBinary(),
            methodContext.methodName(),
            methodContext.methodSignature(),
            null, // method_id: populated by T13
            call.inLambda(),
            line,
            endLine,
            column,
            methodContext.methodStartLine(),
            methodContext.methodEndLine(),
            call.api(),
            call.detection(),
            call.loggerExpr(),
            call.loggerName(),
            call.loggerNameKind(),
            call.level(),
            call.levelDynamic(),
            extracted.templateRaw(),
            template,
            extracted.templateKind(),
            extracted.unsupportedReason(),
            regex,
            constantTokens,
            literalLength,
            extracted.placeholderCount(),
            call.throwableArg() != null,
            enclosing,
            control,
            snippet,
            snippetStart,
            null, // github_url: populated by T15
            analyzerVersion,
            analyzedAt
        );
    }
}
