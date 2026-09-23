package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

/**
 * Resolves the enclosing method-like unit of a log statement (0.8): the nearest {@code
 * MethodDeclaration}/{@code ConstructorDeclaration}/static-or-instance {@code InitializerDeclaration},
 * or, for a log call reached only through a field initializer (typically a lambda assigned as a field's
 * default value, since there is no enclosing method there), the field's {@code VariableDeclarator}. A
 * statement inside a lambda belongs to whichever of these encloses the lambda (0.8): lambdas are
 * transparent to this walk, exactly like {@link org.log2code.analyzer.logging.LogCallDetector}'s
 * {@code in_lambda} computation.
 */
public final class MethodContextResolver {

    private MethodContextResolver() {
    }

    public static MethodContext resolve(Node from) {
        Node current = from;
        while (true) {
            Node parent = current.getParentNode()
                .orElseThrow(() -> new IllegalStateException("no enclosing method/constructor/initializer/field for " + from));
            if (parent instanceof MethodDeclaration method) {
                return new MethodContext(method.getNameAsString(), MethodSignatures.of(method),
                    AstLines.startLine(method), AstLines.endLine(method));
            }
            if (parent instanceof ConstructorDeclaration constructor) {
                return new MethodContext("<init>", MethodSignatures.of(constructor),
                    AstLines.startLine(constructor), AstLines.endLine(constructor));
            }
            if (parent instanceof InitializerDeclaration initializer && initializer.isStatic()) {
                return new MethodContext("<clinit>", MethodSignatures.staticInitializer(),
                    AstLines.startLine(initializer), AstLines.endLine(initializer));
            }
            if (parent instanceof InitializerDeclaration initializer) {
                // Instance initializer block: not one of 0.8's four named forms (method/<init>/<clinit>/
                // <field:name>), so this is an additive, non-conflicting extension for an unspecified case.
                return new MethodContext("<instanceinit>", "<instanceinit>()",
                    AstLines.startLine(initializer), AstLines.endLine(initializer));
            }
            if (parent instanceof VariableDeclarator variable && variable.getParentNode().filter(FieldDeclaration.class::isInstance).isPresent()) {
                String fieldName = variable.getNameAsString();
                return new MethodContext("<field:" + fieldName + ">", MethodSignatures.fieldInitializer(fieldName),
                    AstLines.startLine(variable), AstLines.endLine(variable));
            }
            current = parent;
        }
    }

    /**
     * Whether {@code parent} is one of this resolver's stopping points for {@code current} - shared with
     * {@link EnclosingBlockResolver}, whose own walk is bounded by the same method-like unit.
     */
    static boolean isBoundary(Node parent, Node current) {
        if (parent instanceof MethodDeclaration || parent instanceof ConstructorDeclaration || parent instanceof InitializerDeclaration) {
            return true;
        }
        return parent instanceof VariableDeclarator variable
            && variable.getParentNode().filter(FieldDeclaration.class::isInstance).isPresent();
    }
}
