package org.log2code.analyzer.logging;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.Optional;

/**
 * Classifies one method call on an already-resolved logger-typed expression: whether it is a log
 * method at all (step 6 excludes e.g. {@code isDebugEnabled()}, {@code getName()}), and if so its
 * level, message argument(s) and throwable argument (steps 4-5).
 */
interface LogMethodMatcher {

    Optional<MatchedCall> match(MethodCallExpr call);
}
