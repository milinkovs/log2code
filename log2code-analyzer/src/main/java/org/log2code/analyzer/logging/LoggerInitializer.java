package org.log2code.analyzer.logging;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Optional;

/**
 * Matches a logger field/local variable's initializer against the patterns from T08 step 2, to
 * determine {@code loggerName}/{@code loggerNameKind}. Only the initializer's direct call arguments are
 * inspected (e.g. the {@code X.class} in {@code LoggerFactory.getLogger(X.class)}), not the whole
 * expression tree, which is enough for every pattern the task lists and avoids false positives on more
 * elaborate initializers.
 */
final class LoggerInitializer {

    private LoggerInitializer() {
    }

    record Resolved(String loggerName, String loggerNameKind) {
        static final Resolved UNKNOWN = new Resolved(null, LoggerNameKind.UNKNOWN);
    }

    static Resolved resolve(VariableDeclarator variable, TypeDeclaration<?> declaringType, TypeNameResolver resolver) {
        Optional<Expression> initializerOpt = variable.getInitializer();
        if (initializerOpt.isEmpty()) {
            return Resolved.UNKNOWN;
        }
        Expression initializer = initializerOpt.get();
        List<Expression> args = directArguments(initializer);

        Optional<ClassExpr> classLiteral = findClassLiteralArgument(args);
        if (classLiteral.isPresent()) {
            String fqn = resolveClassLiteralFqn(classLiteral.get(), declaringType, resolver);
            return fqn == null ? Resolved.UNKNOWN : new Resolved(fqn, LoggerNameKind.CLASS_LITERAL);
        }

        if (containsNoArgGetClassCall(args)) {
            return new Resolved(ClassFqns.of(declaringType), LoggerNameKind.GET_CLASS);
        }

        Optional<String> stringName = findStringArgument(args, declaringType);
        if (stringName.isPresent()) {
            return new Resolved(stringName.get(), LoggerNameKind.STRING);
        }

        return Resolved.UNKNOWN;
    }

    private static List<Expression> directArguments(Expression initializer) {
        if (initializer instanceof MethodCallExpr mce) {
            return mce.getArguments();
        }
        if (initializer instanceof ObjectCreationExpr oce) {
            return oce.getArguments();
        }
        return List.of();
    }

    /** {@code X.class} directly, or {@code X.class.getName()} (Logger.getLogger(X.class.getName())). */
    private static Optional<ClassExpr> findClassLiteralArgument(List<Expression> args) {
        for (Expression arg : args) {
            if (arg instanceof ClassExpr classExpr) {
                return Optional.of(classExpr);
            }
            if (arg instanceof MethodCallExpr mce && mce.getScope().filter(ClassExpr.class::isInstance).isPresent()) {
                return Optional.of((ClassExpr) mce.getScope().get());
            }
        }
        return Optional.empty();
    }

    private static String resolveClassLiteralFqn(ClassExpr classExpr, TypeDeclaration<?> declaringType, TypeNameResolver resolver) {
        Type type = classExpr.getType();
        if (!(type instanceof ClassOrInterfaceType coit)) {
            return null; // e.g. "int.class" - never our case
        }
        String raw = TypeNameResolver.rawName(coit);
        if (raw.equals(declaringType.getNameAsString())) {
            return ClassFqns.of(declaringType);
        }
        return resolver.resolveAny(raw);
    }

    private static boolean containsNoArgGetClassCall(List<Expression> args) {
        for (Expression arg : args) {
            if (arg instanceof MethodCallExpr mce && "getClass".equals(mce.getNameAsString()) && mce.getArguments().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> findStringArgument(List<Expression> args, TypeDeclaration<?> declaringType) {
        for (Expression arg : args) {
            if (arg instanceof StringLiteralExpr literal) {
                return Optional.of(literal.asString());
            }
            if (arg instanceof NameExpr nameExpr) {
                Optional<String> constant = sameClassStringConstant(nameExpr.getNameAsString(), declaringType);
                if (constant.isPresent()) {
                    return constant;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> sameClassStringConstant(String fieldName, TypeDeclaration<?> declaringType) {
        for (BodyDeclaration<?> member : declaringType.getMembers()) {
            if (!(member instanceof FieldDeclaration fieldDecl) || !fieldDecl.isStatic() || !fieldDecl.isFinal()) {
                continue;
            }
            for (VariableDeclarator candidate : fieldDecl.getVariables()) {
                if (!candidate.getNameAsString().equals(fieldName)) {
                    continue;
                }
                Optional<Expression> init = candidate.getInitializer();
                if (init.isPresent() && init.get() instanceof StringLiteralExpr literal) {
                    return Optional.of(literal.asString());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Best-effort API guess from a {@code var}-typed logger's initializer (only needed when there is no
     * declared type to resolve against {@link LoggingApiRegistry} directly): recognizes the factory
     * call/constructor named in T08 step 2, disambiguating {@code LogManager}/{@code Logger} (shared by
     * more than one API) via the compilation unit's imports.
     */
    static Optional<String> guessApiFromInitializer(Expression initializer, TypeNameResolver resolver) {
        if (initializer instanceof ObjectCreationExpr oce && oce.getType().getNameAsString().equals("LogAccessor")) {
            String raw = TypeNameResolver.rawName(oce.getType());
            if ("org.springframework.core.log.LogAccessor".equals(resolver.resolveAny(raw))) {
                return Optional.of(LoggingApi.SPRING_LOG_ACCESSOR);
            }
        }
        if (!(initializer instanceof MethodCallExpr mce) || mce.getScope().isEmpty()) {
            return Optional.empty();
        }
        String qualifier = mce.getScope().get().toString();
        String method = mce.getNameAsString();
        return switch (qualifier + "." + method) {
            case "LoggerFactory.getLogger" -> Optional.of(LoggingApi.SLF4J);
            case "LogFactory.getLog" -> Optional.of(LoggingApi.JCL);
            case "System.getLogger" -> Optional.of(LoggingApi.SYSTEM_LOGGER);
            case "LogManager.getLogger" -> disambiguateByImport(resolver, "LogManager",
                "org.apache.logging.log4j.LogManager", LoggingApi.LOG4J2, "java.util.logging.LogManager", LoggingApi.JUL);
            case "Logger.getLogger" -> disambiguateByImport(resolver, "Logger",
                "java.util.logging.Logger", LoggingApi.JUL, "org.jboss.logging.Logger", LoggingApi.JBOSS_LOGGING);
            default -> Optional.empty();
        };
    }

    private static Optional<String> disambiguateByImport(
        TypeNameResolver resolver, String simpleName, String fqnA, String apiA, String fqnB, String apiB) {
        String resolved = resolver.resolveAny(simpleName);
        if (resolved.equals(fqnA)) {
            return Optional.of(apiA);
        }
        if (resolved.equals(fqnB)) {
            return Optional.of(apiB);
        }
        return Optional.empty();
    }
}
