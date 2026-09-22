package org.log2code.analyzer.logging;

import com.github.javaparser.ast.expr.Expression;
import java.util.List;
import org.log2code.core.model.Level;

/** The level/message/throwable shape of one recognized call, before the logger itself is resolved. */
public record MatchedCall(
    Level level,
    boolean levelDynamic,
    List<Expression> messageArgs,
    Expression throwableArg
) {
}
