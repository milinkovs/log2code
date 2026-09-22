package org.log2code.analyzer.logging;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.log2code.core.model.Level;

/**
 * One detected call to a logging API method (T08 step 7). This is an analyzer-internal type, not part
 * of the OpenSearch schema (0.7): template extraction (T09) and catalog assembly (T10) turn a
 * {@code LogCall} plus its enclosing class/method (found by walking up from {@link #node()}) into a
 * {@code CatalogEntry}.
 *
 * @param node the terminal call of the statement ({@code .info(...)}, or {@code .log(...)} for the
 *     SLF4J fluent API); line/column/end-line come from {@code node.getRange()}
 * @param api one of the {@link LoggingApi} constants
 * @param loggerExpr source text of the logger expression ({@code log}, {@code this.logger}, {@code LOGGER})
 * @param loggerName resolved logger name (FQN or string), or {@code null} if not resolved
 * @param loggerNameKind one of the {@link LoggerNameKind} constants
 * @param level the parsed level, or {@link Level#UNKNOWN} if {@code levelDynamic}
 * @param levelDynamic {@code true} if the level is a runtime expression, not a literal
 * @param messageArgs the message-related arguments in source order (e.g. format string + placeholder
 *     values, or a single {@code Supplier}); template extraction (T09) interprets these further
 * @param throwableArg the throwable argument, or {@code null} if none was detected
 * @param detection one of the {@link Detection} constants
 * @param inLambda {@code true} if {@code node} is lexically inside a lambda body
 */
public record LogCall(
    MethodCallExpr node,
    String api,
    String loggerExpr,
    String loggerName,
    String loggerNameKind,
    Level level,
    boolean levelDynamic,
    List<Expression> messageArgs,
    Expression throwableArg,
    String detection,
    boolean inLambda
) {
}
