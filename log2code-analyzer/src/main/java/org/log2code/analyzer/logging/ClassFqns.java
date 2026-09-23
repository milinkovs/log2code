package org.log2code.analyzer.logging;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Computes a dotted class FQN (0.8: {@code a.b.Outer.Inner}) by walking a type declaration's own
 * nesting chain, and finds the chain of enclosing named types from an arbitrary AST node (used by T08
 * pass 2 to look up "fields visible from here", including a class implicitly reachable through an
 * anonymous-class or lambda body). Public (T09 note, 0.13): message-template constant resolution
 * (rule 3) reuses this to find a log call's enclosing class chain, without duplicating the walk.
 * {@link #binaryOf(TypeDeclaration)} (T10 note, 0.13) computes the sibling binary form ({@code
 * a.b.Outer$Inner}), reusing the same nesting walk.
 */
public final class ClassFqns {

    private ClassFqns() {
    }

    public static String of(TypeDeclaration<?> type) {
        return walk(type, ".");
    }

    /** The binary-name form (0.8: {@code a.b.Outer$Inner}): package stays dotted, nesting joins with {@code $}. */
    public static String binaryOf(TypeDeclaration<?> type) {
        return walk(type, "$");
    }

    private static String walk(TypeDeclaration<?> type, String nestingSeparator) {
        List<String> segments = new ArrayList<>();
        segments.add(type.getNameAsString());
        Node current = type;
        while (true) {
            Optional<Node> parent = current.getParentNode();
            if (parent.isEmpty()) {
                return String.join(nestingSeparator, segments);
            }
            Node p = parent.get();
            if (p instanceof TypeDeclaration<?> parentType) {
                segments.add(0, parentType.getNameAsString());
                current = p;
            } else if (p instanceof CompilationUnit cu) {
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                String joined = String.join(nestingSeparator, segments);
                return pkg.isEmpty() ? joined : pkg + "." + joined;
            } else {
                current = p; // skip local-class/anonymous-class/method wrappers transparently
            }
        }
    }

    /** Enclosing named type declarations of {@code from}, innermost first (anonymous classes are transparent). */
    public static List<TypeDeclaration<?>> enclosingTypesInnerToOuter(Node from) {
        List<TypeDeclaration<?>> result = new ArrayList<>();
        Node current = from;
        while (true) {
            Optional<Node> parent = current.getParentNode();
            if (parent.isEmpty()) {
                return result;
            }
            current = parent.get();
            if (current instanceof TypeDeclaration<?> typeDecl) {
                result.add(typeDecl);
            }
        }
    }
}
