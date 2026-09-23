package org.log2code.analyzer.template;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import java.util.List;
import java.util.Optional;
import org.log2code.analyzer.logging.ClassFqns;

/**
 * Resolves a {@code NameExpr}/{@code FieldAccessExpr} reference to the {@code static final String}
 * field it names, against a {@link ConstantIndex} (T09 rule 3). No symbol solver is used (ADR-008's
 * reasoning applies equally here): resolution covers the same-class and enclosing-class case, a
 * {@code static import}, and a simple {@code SimpleClassName.FIELD} reference to a type declared in
 * the same compilation unit or reached through a single-type import - each restricted to classes the
 * {@link ConstantIndex} actually knows (i.e. within the same code unit), never a guess about an
 * external type's fields.
 */
final class ConstantResolver {

    private final ConstantIndex index;

    ConstantResolver(ConstantIndex index) {
        this.index = index;
    }

    Optional<Expression> resolveInitializer(Expression ref) {
        if (ref instanceof NameExpr nameExpr) {
            return resolveByName(nameExpr);
        }
        if (ref instanceof FieldAccessExpr fieldAccess) {
            return resolveByFieldAccess(fieldAccess);
        }
        return Optional.empty();
    }

    private Optional<Expression> resolveByName(NameExpr ref) {
        String name = ref.getNameAsString();
        for (TypeDeclaration<?> type : ClassFqns.enclosingTypesInnerToOuter(ref)) {
            Optional<Expression> found = index.field(ClassFqns.of(type), name);
            if (found.isPresent()) {
                return found;
            }
        }
        Optional<CompilationUnit> unit = ref.findCompilationUnit();
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        for (ImportDeclaration importDecl : unit.get().getImports()) {
            if (importDecl.isStatic() && !importDecl.isAsterisk()
                && importDecl.getName().getIdentifier().equals(name)) {
                Optional<String> classFqn = importDecl.getName().getQualifier().map(q -> q.asString());
                Optional<Expression> found = classFqn.flatMap(fqn -> index.field(fqn, name));
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Expression> resolveByFieldAccess(FieldAccessExpr ref) {
        String name = ref.getNameAsString();
        return resolveScopeClassFqn(ref.getScope(), ref).flatMap(fqn -> index.field(fqn, name));
    }

    private Optional<String> resolveScopeClassFqn(Expression scope, Node context) {
        if (scope instanceof ThisExpr) {
            List<TypeDeclaration<?>> enclosing = ClassFqns.enclosingTypesInnerToOuter(context);
            return enclosing.isEmpty() ? Optional.empty() : Optional.of(ClassFqns.of(enclosing.get(0)));
        }
        if (scope instanceof NameExpr nameExpr) {
            return resolveSimpleClassName(nameExpr.getNameAsString(), context);
        }
        return Optional.empty();
    }

    private Optional<String> resolveSimpleClassName(String simpleName, Node context) {
        Optional<CompilationUnit> unit = context.findCompilationUnit();
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        for (TypeDeclaration<?> type : unit.get().findAll(TypeDeclaration.class)) {
            if (type.getNameAsString().equals(simpleName)) {
                String fqn = ClassFqns.of(type);
                if (index.hasClass(fqn)) {
                    return Optional.of(fqn);
                }
            }
        }
        for (ImportDeclaration importDecl : unit.get().getImports()) {
            if (!importDecl.isStatic() && !importDecl.isAsterisk()
                && importDecl.getName().getIdentifier().equals(simpleName)) {
                String fqn = importDecl.getNameAsString();
                if (index.hasClass(fqn)) {
                    return Optional.of(fqn);
                }
            }
        }
        return Optional.empty();
    }
}
