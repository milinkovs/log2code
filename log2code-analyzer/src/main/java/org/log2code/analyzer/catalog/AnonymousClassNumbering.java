package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Numbers anonymous classes the way {@code javac} does, as a "best estimate" (0.8): each named type
 * (top-level or nested) has its own counter, starting at 1, assigned in source order to the anonymous
 * classes declared directly within it - an anonymous class nested inside another anonymous class shares
 * its nearest enclosing <em>named</em> type's counter, since anonymous classes have no counter of their
 * own. {@link ClassContextResolver} appends the number to that named type's binary name (T10, ADR-010).
 * Public (T13 note, 0.13): the call graph package recomputes this per file, on demand, for whichever
 * compilation unit a resolved call target's declaration turns out to live in.
 */
public final class AnonymousClassNumbering {

    private AnonymousClassNumbering() {
    }

    /** Maps every anonymous-class-bearing {@link ObjectCreationExpr} in {@code unit} to its 1-based number. */
    public static Map<Node, Integer> compute(CompilationUnit unit) {
        Map<TypeDeclaration<?>, Integer> countersByOwner = new IdentityHashMap<>();
        Map<Node, Integer> result = new IdentityHashMap<>();
        for (ObjectCreationExpr call : unit.findAll(ObjectCreationExpr.class)) {
            if (call.getAnonymousClassBody().isEmpty()) {
                continue;
            }
            TypeDeclaration<?> owner = nearestEnclosingNamedType(call);
            int next = countersByOwner.merge(owner, 1, Integer::sum);
            result.put(call, next);
        }
        return result;
    }

    static TypeDeclaration<?> nearestEnclosingNamedType(Node from) {
        Node current = from;
        while (true) {
            Node parent = current.getParentNode()
                .orElseThrow(() -> new IllegalStateException("no enclosing named type for " + from));
            if (parent instanceof TypeDeclaration<?> type) {
                return type;
            }
            current = parent;
        }
    }
}
