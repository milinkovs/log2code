package org.log2code.analyzer.catalog;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

/**
 * Reads 1-based line/column numbers off a parsed node's {@link Range} (0.7's {@code line}/
 * {@code end_line}/{@code column}). Public (T13 note, 0.13): the call graph package needs the exact
 * same line convention to match a {@code control.calls_before} {@code CallSite} back to the
 * {@code MethodInfo.calls[]} entry it corresponds to.
 */
public final class AstLines {

    private AstLines() {
    }

    public static int startLine(Node node) {
        return node.getRange().map(r -> r.begin.line).orElse(-1);
    }

    public static int endLine(Node node) {
        return node.getRange().map(r -> r.end.line).orElse(-1);
    }

    public static int startColumn(Node node) {
        return node.getRange().map(r -> r.begin.column).orElse(-1);
    }
}
