package org.log2code.analyzer.template;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.log2code.analyzer.logging.LogCall;
import org.log2code.analyzer.logging.LoggingApi;
import org.log2code.core.template.MessageTemplate;
import org.log2code.core.template.MessageTemplate.Hole;
import org.log2code.core.template.MessageTemplate.Part;

/**
 * Turns one {@link LogCall}'s message argument into an {@link ExtractedTemplate} (T09): applies rules
 * 1-9 (0.9/T09) to the AST of {@code call.messageArgs().get(0)}, using a {@link Style} determined from
 * the call's {@code logging_api} (and, for JUL/JBoss, its exact method) to decide how a literal
 * fragment's text is scanned for placeholders.
 *
 * <p>The design has two levels, matching how the rules read: {@link #classifyMessage} recognizes the
 * "whole expression" shapes - format-style calls (rule 4), a supplier/lambda (rule 6), a
 * {@code StringBuilder} chain (rule 7), string concatenation (rule 2) - and only these can set the
 * overall {@code template_kind}. Anything nested inside a concatenation or {@code StringBuilder} chain
 * goes through {@link #concatTermParts}, which is deliberately narrower per rule 2's own text ("ostalo
 * postaje rupa"): a literal, a resolvable {@code static final String} constant (rule 3), or else a
 * hole - it does not recursively look for format-calls/suppliers/chains inside a term.
 */
public final class MessageTemplateExtractor {

    /** Guards against runaway/cyclic constant resolution (rule 3); real Java code never nests this deep. */
    private static final int MAX_CONSTANT_DEPTH = 16;

    private MessageTemplateExtractor() {
    }

    public static ExtractedTemplate extract(LogCall call, ConstantIndex constants) {
        Expression messageExpr = call.messageArgs().get(0);
        String templateRaw = messageExpr.toString();
        Style style = determineStyle(call);
        ConstantResolver resolver = new ConstantResolver(constants);

        Result result = classifyMessage(messageExpr, style, resolver, 0);
        if (result.unsupportedReason() != null) {
            return new ExtractedTemplate(templateRaw, null, TemplateKind.UNSUPPORTED, result.unsupportedReason(), 0);
        }
        MessageTemplate template = MessageTemplate.of(result.parts());
        return new ExtractedTemplate(templateRaw, template, result.kind(), null, template.holeCount());
    }

    // --- Style (which placeholder syntax a literal fragment's text is scanned for) ---------------

    private static Style determineStyle(LogCall call) {
        return switch (call.api()) {
            case LoggingApi.SLF4J, LoggingApi.LOG4J2, LoggingApi.UNKNOWN -> Style.BRACES;
            case LoggingApi.JBOSS_LOGGING -> jbossStyle(call);
            case LoggingApi.JUL -> call.messageArgs().size() > 1 ? Style.MESSAGE_FORMAT : Style.NONE;
            default -> Style.NONE; // JCL, tomcat-juli, spring-log-accessor, system-logger
        };
    }

    private static Style jbossStyle(LogCall call) {
        String name = call.node().getNameAsString();
        char suffix = name.charAt(name.length() - 1);
        if (suffix == 'f') {
            return Style.FORMAT;
        }
        if (suffix == 'v') {
            return Style.MESSAGE_FORMAT;
        }
        return Style.BRACES;
    }

    // --- Top-level dispatch (rules 1-9): only this level can set template_kind -------------------

    private record Result(String kind, List<Part> parts, String unsupportedReason) {
        static Result of(String kind, List<Part> parts) {
            return new Result(kind, parts, null);
        }

        static Result unsupported(String reason) {
            return new Result(null, null, reason);
        }
    }

    private static Result classifyMessage(Expression expr, Style style, ConstantResolver resolver, int depth) {
        Expression e = unwrap(expr);

        if (isTomcatStringManagerCall(e)) {
            return Result.unsupported(UnsupportedReason.TOMCAT_STRING_MANAGER);
        }

        Optional<Expression> fmtArg = matchFormatCallShape(e);
        if (fmtArg.isPresent()) {
            return Result.of(TemplateKind.FORMAT, concatOrLeafParts(fmtArg.get(), Style.FORMAT, resolver, depth));
        }

        Optional<Expression> supplierBody = matchSupplierShape(e);
        if (supplierBody.isPresent()) {
            Result inner = classifyMessage(supplierBody.get(), style, resolver, depth + 1);
            if (inner.unsupportedReason() != null) {
                return inner;
            }
            return Result.of(TemplateKind.SUPPLIER, inner.parts());
        }

        Optional<List<Expression>> sbTerms = matchStringBuilderChainShape(e);
        if (sbTerms.isPresent()) {
            return Result.of(TemplateKind.CONCAT, concatParts(sbTerms.get(), style, resolver, depth));
        }

        if (isPlusConcat(e)) {
            return Result.of(TemplateKind.CONCAT, concatParts(flattenConcatTerms(e), style, resolver, depth));
        }

        return leafResult(e, style, resolver, depth);
    }

    /** Rule 1 (or its rule-5/style variant) for a plain literal, rule 3 for a resolvable constant, else rule 8. */
    private static Result leafResult(Expression e, Style style, ConstantResolver resolver, int depth) {
        if (isStringOrTextBlockLiteral(e)) {
            List<Part> parts = scanLiteral(literalText(e), style);
            return Result.of(kindForStyle(style, parts), parts);
        }
        if (isNameOrFieldAccess(e) && depth < MAX_CONSTANT_DEPTH) {
            Optional<String> literalValue = resolveFullyLiteralConstant(e, resolver, depth);
            if (literalValue.isPresent()) {
                List<Part> parts = scanLiteral(literalValue.get(), style);
                return Result.of(kindForStyle(style, parts), parts);
            }
        }
        // rule 8: ternary and everything else becomes a single hole, i.e. "dynamic".
        return Result.of(TemplateKind.DYNAMIC, List.of(new Hole()));
    }

    /** The fmt argument of a rule-4 format-style call: concatenation or a single literal/constant/hole - never a nested format-call/supplier/chain. */
    private static List<Part> concatOrLeafParts(Expression expr, Style style, ConstantResolver resolver, int depth) {
        Expression e = unwrap(expr);
        if (isPlusConcat(e)) {
            return concatParts(flattenConcatTerms(e), style, resolver, depth);
        }
        return leafResult(e, style, resolver, depth).parts();
    }

    private static String kindForStyle(Style style, List<Part> parts) {
        return switch (style) {
            case NONE -> TemplateKind.LITERAL;
            case BRACES -> holeCount(parts) > 0 ? TemplateKind.PLACEHOLDERS : TemplateKind.LITERAL;
            case FORMAT -> TemplateKind.FORMAT;
            case MESSAGE_FORMAT -> TemplateKind.MESSAGE_FORMAT;
        };
    }

    private static int holeCount(List<Part> parts) {
        int count = 0;
        for (Part part : parts) {
            if (part instanceof Hole) {
                count++;
            }
        }
        return count;
    }

    // --- Concatenation / StringBuilder-chain terms (rules 2, 7): literal, constant, or hole -------

    private static List<Part> concatParts(List<Expression> terms, Style style, ConstantResolver resolver, int depth) {
        List<Part> parts = new ArrayList<>();
        for (Expression term : terms) {
            parts.addAll(concatTermParts(term, style, resolver, depth));
        }
        return parts;
    }

    private static List<Part> concatTermParts(Expression term, Style style, ConstantResolver resolver, int depth) {
        Expression e = unwrap(term);
        if (isStringOrTextBlockLiteral(e)) {
            return scanLiteral(literalText(e), style);
        }
        if (e instanceof CharLiteralExpr charLiteral) {
            return scanLiteral(String.valueOf(charLiteral.asChar()), style);
        }
        if (isNumericLiteral(e)) {
            return List.of(new MessageTemplate.Literal(numericText(e)));
        }
        if (isNameOrFieldAccess(e) && depth < MAX_CONSTANT_DEPTH) {
            Optional<String> literalValue = resolveFullyLiteralConstant(e, resolver, depth);
            if (literalValue.isPresent()) {
                return scanLiteral(literalValue.get(), style);
            }
        }
        return List.of(new Hole());
    }

    private static List<Part> scanLiteral(String text, Style style) {
        return switch (style) {
            case NONE -> text.isEmpty() ? List.of() : List.of(new MessageTemplate.Literal(text));
            case BRACES -> MessageTemplate.scanRawParts(text);
            case FORMAT -> FormatScanner.scan(text);
            case MESSAGE_FORMAT -> MessageFormatScanner.scan(text);
        };
    }

    // --- Rule 3: static final String constants, substituted only if their own value is fully literal ---

    private static boolean isNameOrFieldAccess(Expression e) {
        return e instanceof NameExpr || e instanceof FieldAccessExpr;
    }

    private static Optional<String> resolveFullyLiteralConstant(Expression ref, ConstantResolver resolver, int depth) {
        return resolver.resolveInitializer(ref).flatMap(init -> tryFullyLiteralText(init, resolver, depth + 1));
    }

    /**
     * Rule 3's "literal ili konkatenacija literala" test: {@code empty()} unless every leaf of
     * {@code expr} is itself a literal or a (transitively) fully-literal constant - in which case a
     * reference to it becomes a single hole, not a partial substitution.
     */
    private static Optional<String> tryFullyLiteralText(Expression expr, ConstantResolver resolver, int depth) {
        if (depth >= MAX_CONSTANT_DEPTH) {
            return Optional.empty();
        }
        Expression e = unwrap(expr);
        if (isStringOrTextBlockLiteral(e)) {
            return Optional.of(literalText(e));
        }
        if (e instanceof CharLiteralExpr charLiteral) {
            return Optional.of(String.valueOf(charLiteral.asChar()));
        }
        if (isNumericLiteral(e)) {
            return Optional.of(numericText(e));
        }
        if (isPlusConcat(e)) {
            StringBuilder combined = new StringBuilder();
            for (Expression term : flattenConcatTerms(e)) {
                Optional<String> termText = tryFullyLiteralText(term, resolver, depth + 1);
                if (termText.isEmpty()) {
                    return Optional.empty();
                }
                combined.append(termText.get());
            }
            return Optional.of(combined.toString());
        }
        if (isNameOrFieldAccess(e)) {
            return resolver.resolveInitializer(e).flatMap(init -> tryFullyLiteralText(init, resolver, depth + 1));
        }
        return Optional.empty();
    }

    // --- Shape detection: rule 4 (format-style calls), rule 6 (supplier/lambda), rule 7 (StringBuilder), rule 9 (unsupported) ---

    private static Optional<Expression> matchFormatCallShape(Expression e) {
        if (!(e instanceof MethodCallExpr call)) {
            return Optional.empty();
        }
        String name = call.getNameAsString();
        Optional<Expression> scope = call.getScope();
        if ("format".equals(name) && scope.isPresent() && isSimpleNamed(scope.get(), "String", "LogMessage")) {
            NodeList<Expression> args = call.getArguments();
            if (!args.isEmpty()) {
                return Optional.of(args.get(0));
            }
        }
        if ("formatted".equals(name) && scope.isPresent()) {
            return Optional.of(scope.get());
        }
        return Optional.empty();
    }

    private static boolean isSimpleNamed(Expression scope, String... names) {
        String text = scope.toString();
        for (String name : names) {
            if (name.equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Expression> matchSupplierShape(Expression e) {
        if (e instanceof LambdaExpr lambda) {
            return lambdaBodyExpression(lambda);
        }
        if (e instanceof MethodCallExpr call && "of".equals(call.getNameAsString())
            && call.getScope().isPresent() && isSimpleNamed(call.getScope().get(), "LogMessage")
            && call.getArguments().size() == 1
            && call.getArguments().get(0) instanceof LambdaExpr lambda) {
            return lambdaBodyExpression(lambda);
        }
        return Optional.empty();
    }

    /** Rule 6: the lambda's expression body, or the sole {@code return}'s expression of a single-statement block body. */
    private static Optional<Expression> lambdaBodyExpression(LambdaExpr lambda) {
        Optional<Expression> direct = lambda.getExpressionBody();
        if (direct.isPresent()) {
            return direct;
        }
        if (lambda.getBody() instanceof BlockStmt block && block.getStatements().size() == 1
            && block.getStatements().get(0) instanceof ReturnStmt returnStmt) {
            return returnStmt.getExpression();
        }
        return Optional.empty();
    }

    private static Optional<List<Expression>> matchStringBuilderChainShape(Expression e) {
        if (!(e instanceof MethodCallExpr call) || !"toString".equals(call.getNameAsString())
            || !call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return Optional.empty();
        }
        List<Expression> terms = new ArrayList<>();
        Expression cursor = call.getScope().get();
        while (cursor instanceof MethodCallExpr step && "append".equals(step.getNameAsString())
            && step.getArguments().size() == 1) {
            terms.add(0, step.getArguments().get(0));
            cursor = step.getScope().orElse(null);
            if (cursor == null) {
                return Optional.empty();
            }
        }
        if (!(cursor instanceof ObjectCreationExpr creation)
            || !"StringBuilder".equals(creation.getType().getNameAsString())) {
            return Optional.empty();
        }
        NodeList<Expression> ctorArgs = creation.getArguments();
        if (ctorArgs.size() == 1 && !(ctorArgs.get(0) instanceof IntegerLiteralExpr)) {
            terms.add(0, ctorArgs.get(0));
        }
        return terms.isEmpty() ? Optional.empty() : Optional.of(terms);
    }

    /** {@code sm.getString(...)}: Tomcat's {@code StringManager}, by the naming convention the task specifies. */
    private static boolean isTomcatStringManagerCall(Expression e) {
        if (!(e instanceof MethodCallExpr call) || !"getString".equals(call.getNameAsString())) {
            return false;
        }
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        Expression s = scope.get();
        String simpleName = s instanceof NameExpr n ? n.getNameAsString()
            : s instanceof FieldAccessExpr f ? f.getNameAsString() : null;
        return "sm".equals(simpleName);
    }

    // --- Rule 2: string concatenation -------------------------------------------------------------

    private static boolean isPlusConcat(Expression e) {
        return e instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS;
    }

    private static List<Expression> flattenConcatTerms(Expression e) {
        List<Expression> terms = new ArrayList<>();
        flattenConcatTerms(e, terms);
        return terms;
    }

    private static void flattenConcatTerms(Expression e, List<Expression> out) {
        Expression unwrapped = unwrap(e);
        if (isPlusConcat(unwrapped)) {
            BinaryExpr binary = (BinaryExpr) unwrapped;
            flattenConcatTerms(binary.getLeft(), out);
            flattenConcatTerms(binary.getRight(), out);
        } else {
            out.add(unwrapped);
        }
    }

    // --- Literal helpers ---------------------------------------------------------------------------

    private static Expression unwrap(Expression e) {
        Expression current = e;
        while (current instanceof EnclosedExpr enclosed) {
            current = enclosed.getInner();
        }
        return current;
    }

    private static boolean isStringOrTextBlockLiteral(Expression e) {
        return e instanceof StringLiteralExpr || e instanceof TextBlockLiteralExpr;
    }

    private static String literalText(Expression e) {
        if (e instanceof StringLiteralExpr s) {
            return s.asString();
        }
        if (e instanceof TextBlockLiteralExpr t) {
            return t.asString();
        }
        throw new IllegalArgumentException("not a string/text-block literal: " + e);
    }

    private static boolean isNumericLiteral(Expression e) {
        return e instanceof IntegerLiteralExpr || e instanceof LongLiteralExpr || e instanceof DoubleLiteralExpr;
    }

    private static String numericText(Expression e) {
        if (e instanceof IntegerLiteralExpr i) {
            return i.asNumber().toString();
        }
        if (e instanceof LongLiteralExpr l) {
            return l.asNumber().toString();
        }
        if (e instanceof DoubleLiteralExpr d) {
            return String.valueOf(d.asDouble());
        }
        throw new IllegalArgumentException("not a numeric literal: " + e);
    }
}
