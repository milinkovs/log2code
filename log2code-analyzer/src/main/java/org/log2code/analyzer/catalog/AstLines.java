package org.log2code.analyzer.catalog;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

/** Reads 1-based line/column numbers off a parsed node's {@link Range} (0.7's {@code line}/{@code end_line}/{@code column}). */
final class AstLines {

    private AstLines() {
    }

    static int startLine(Node node) {
        return node.getRange().map(r -> r.begin.line).orElse(-1);
    }

    static int endLine(Node node) {
        return node.getRange().map(r -> r.end.line).orElse(-1);
    }

    static int startColumn(Node node) {
        return node.getRange().map(r -> r.begin.column).orElse(-1);
    }
}
