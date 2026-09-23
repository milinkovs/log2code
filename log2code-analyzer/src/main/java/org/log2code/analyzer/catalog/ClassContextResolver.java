package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.Map;
import org.log2code.analyzer.logging.ClassFqns;

/**
 * Resolves the enclosing class of a log statement (0.8): a nested named class is {@code Outer.Inner} /
 * {@code Outer$Inner} ({@link ClassFqns}); an anonymous class has no dotted form, so both
 * {@code class_fqn} and {@code class_binary} become its estimated binary name, {@code
 * <nearest-named-type-binary>$N} (see {@link AnonymousClassNumbering}, ADR-010).
 */
public final class ClassContextResolver {

    private ClassContextResolver() {
    }

    public static ClassContext resolve(Node from, Map<Node, Integer> anonymousClassNumbers) {
        Node current = from;
        while (true) {
            Node parent = current.getParentNode()
                .orElseThrow(() -> new IllegalStateException("no enclosing type for " + from));
            if (parent instanceof TypeDeclaration<?> type) {
                return new ClassContext(ClassFqns.of(type), ClassFqns.binaryOf(type));
            }
            if (parent instanceof ObjectCreationExpr call && current instanceof BodyDeclaration<?>
                && call.getAnonymousClassBody().isPresent()) {
                TypeDeclaration<?> owner = AnonymousClassNumbering.nearestEnclosingNamedType(call);
                int number = anonymousClassNumbers.getOrDefault(call, 0);
                String binary = ClassFqns.binaryOf(owner) + "$" + number;
                return new ClassContext(binary, binary);
            }
            current = parent;
        }
    }
}
