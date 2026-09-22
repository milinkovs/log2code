package org.log2code.analyzer.logging;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VarType;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pass 2 (T08 step 3): resolves the logger a call site's scope expression refers to - a local variable,
 * a field of the enclosing class or an outer enclosing class, or (walking up, within the same code
 * unit) a field inherited from a superclass. Returns empty when none of that applies directly, in
 * which case {@link LogCallDetector} falls back to the heuristic (step 3.3) using
 * {@link #candidateNameForHeuristic(Expression)}.
 */
final class LoggerFieldResolver {

    private LoggerFieldResolver() {
    }

    record Resolution(String api, String loggerName, String loggerNameKind, String detection) {
    }

    static Optional<Resolution> resolve(Expression scope, MethodCallExpr callNode, TypeIndex typeIndex, TypeNameResolver resolver) {
        if (scope instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            Optional<TypeDeclaration<?>> enclosing = innermostEnclosingType(callNode);
            if (enclosing.isPresent()) {
                Optional<Resolution> local = resolveLocalVariable(name, callNode, enclosing.get(), resolver);
                if (local.isPresent()) {
                    return local;
                }
            }
            return resolveFieldByName(name, callNode, typeIndex);
        }
        if (scope instanceof FieldAccessExpr fieldAccess) {
            String name = fieldAccess.getNameAsString();
            if (fieldAccess.getScope() instanceof ThisExpr) {
                return resolveFieldByName(name, callNode, typeIndex);
            }
            if (fieldAccess.getScope() instanceof NameExpr typeCandidate && startsUppercase(typeCandidate.getNameAsString())) {
                Optional<String> targetFqn = resolver.resolve(typeCandidate.getNameAsString(), typeIndex.knownFqns());
                if (targetFqn.isPresent()) {
                    return fieldInClass(name, targetFqn.get(), typeIndex, Detection.TYPED);
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** The receiver name to test against the heuristic pattern (step 3.3), if the scope shape supports it at all. */
    static Optional<String> candidateNameForHeuristic(Expression scope) {
        if (scope instanceof NameExpr nameExpr) {
            return Optional.of(nameExpr.getNameAsString());
        }
        if (scope instanceof FieldAccessExpr fieldAccess && fieldAccess.getScope() instanceof ThisExpr) {
            return Optional.of(fieldAccess.getNameAsString());
        }
        return Optional.empty();
    }

    private static boolean startsUppercase(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    // ---- fields: own class of each enclosing type, then its superclass chain, outward ----

    private static Optional<Resolution> resolveFieldByName(String name, Node callSite, TypeIndex typeIndex) {
        for (TypeDeclaration<?> enclosing : ClassFqns.enclosingTypesInnerToOuter(callSite)) {
            String enclosingFqn = ClassFqns.of(enclosing);
            Optional<Resolution> own = fieldInClass(name, enclosingFqn, typeIndex, Detection.TYPED);
            if (own.isPresent()) {
                return own;
            }
            Optional<Resolution> inherited = fieldInSuperclassChain(name, enclosingFqn, typeIndex);
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        return Optional.empty();
    }

    private static Optional<Resolution> fieldInClass(String name, String classFqn, TypeIndex typeIndex, String detection) {
        return typeIndex.get(classFqn)
            .map(ClassInfo::loggerFields)
            .map(fields -> fields.get(name))
            .map(info -> new Resolution(info.api(), info.loggerName(), info.loggerNameKind(), detection));
    }

    private static Optional<Resolution> fieldInSuperclassChain(String name, String startClassFqn, TypeIndex typeIndex) {
        Optional<ClassInfo> current = typeIndex.get(startClassFqn);
        Set<String> visited = new HashSet<>();
        while (current.isPresent()) {
            String superFqn = current.get().superclassFqn();
            if (superFqn == null || !visited.add(superFqn)) {
                return Optional.empty();
            }
            Optional<Resolution> found = fieldInClass(name, superFqn, typeIndex, Detection.INHERITED);
            if (found.isPresent()) {
                return found;
            }
            current = typeIndex.get(superFqn);
        }
        return Optional.empty();
    }

    // ---- local variables: walk enclosing blocks, up to the method/constructor/initializer boundary ----

    private static Optional<TypeDeclaration<?>> innermostEnclosingType(Node from) {
        List<TypeDeclaration<?>> chain = ClassFqns.enclosingTypesInnerToOuter(from);
        return chain.isEmpty() ? Optional.empty() : Optional.of(chain.get(0));
    }

    private static Optional<Resolution> resolveLocalVariable(
        String name, Node callSite, TypeDeclaration<?> enclosingType, TypeNameResolver resolver) {
        Node current = callSite;
        while (true) {
            Optional<Node> parentOpt = current.getParentNode();
            if (parentOpt.isEmpty()) {
                return Optional.empty();
            }
            Node parent = parentOpt.get();
            if (parent instanceof BlockStmt block) {
                int idx = block.getStatements().indexOf(current);
                for (int i = idx - 1; i >= 0; i--) {
                    Optional<VariableDeclarator> match = declaredIn(block.getStatement(i), name);
                    if (match.isPresent()) {
                        return resolveFromDeclarator(match.get(), enclosingType, resolver);
                    }
                }
            }
            if (parent instanceof CallableDeclaration<?> || parent instanceof InitializerDeclaration) {
                return Optional.empty();
            }
            current = parent;
        }
    }

    private static Optional<VariableDeclarator> declaredIn(Statement statement, String name) {
        if (statement instanceof ExpressionStmt exprStmt
            && exprStmt.getExpression() instanceof VariableDeclarationExpr varDecl) {
            for (VariableDeclarator candidate : varDecl.getVariables()) {
                if (candidate.getNameAsString().equals(name)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Resolution> resolveFromDeclarator(
        VariableDeclarator declarator, TypeDeclaration<?> enclosingType, TypeNameResolver resolver) {
        Type declaredType = declarator.getType();
        if (declaredType instanceof ClassOrInterfaceType coit) {
            Optional<LoggerTypeSpec> spec = resolver.resolve(TypeNameResolver.rawName(coit), LoggingApiRegistry.knownFqns())
                .flatMap(LoggingApiRegistry::forType);
            if (spec.isEmpty()) {
                return Optional.empty();
            }
            LoggerInitializer.Resolved init = LoggerInitializer.resolve(declarator, enclosingType, resolver);
            return Optional.of(new Resolution(spec.get().api(), init.loggerName(), init.loggerNameKind(), Detection.TYPED));
        }
        if (declaredType instanceof VarType) {
            Optional<Expression> initializer = declarator.getInitializer();
            if (initializer.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> api = LoggerInitializer.guessApiFromInitializer(initializer.get(), resolver);
            if (api.isEmpty()) {
                return Optional.empty();
            }
            LoggerInitializer.Resolved init = LoggerInitializer.resolve(declarator, enclosingType, resolver);
            return Optional.of(new Resolution(api.get(), init.loggerName(), init.loggerNameKind(), Detection.TYPED));
        }
        return Optional.empty();
    }
}
