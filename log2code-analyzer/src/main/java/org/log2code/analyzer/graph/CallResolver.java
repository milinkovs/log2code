package org.log2code.analyzer.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.log2code.analyzer.catalog.AstLines;
import org.log2code.analyzer.catalog.ControlContextExtractor;
import org.log2code.core.model.CallEdge;

/**
 * Resolves one call site to zero, one or two {@link CallEdge}s (T13 steps 2-3). {@code resolve()} runs
 * in its own {@code try/catch}, exactly as the task requires: {@code UnsolvedSymbolException} (a type
 * genuinely could not be found - the ordinary "external, un-jar'd dependency" case; it is a {@link
 * RuntimeException}, so catching that alone already covers it), any other {@code RuntimeException} the
 * resolver throws for a construct it does not model, and {@link StackOverflowError} (deeply
 * generic/recursive type inference in some framework code can blow the resolver's own stack) are all
 * treated the same way: {@code resolved=false}, and the failure is simply counted, never rethrown, so
 * one bad call site never aborts the whole module.
 */
final class CallResolver {

    private CallResolver() {
    }

    static List<CallEdge> resolve(Node callNode, ProjectMethodIds methodIds, ProjectInterfaceIndex interfaceIndex,
                                   Map<String, TypeDeclaration<?>> declarationsByFqn) {
        int line = AstLines.startLine(callNode);
        String text = ControlContextExtractor.truncate(callNode.toString());
        try {
            ResolvedMethodLikeDeclaration declaration = resolveNode(callNode);
            return edgesFor(line, text, declaration, methodIds, interfaceIndex, declarationsByFqn);
        } catch (RuntimeException | StackOverflowError e) {
            return List.of(new CallEdge(line, text, null, null, false, false));
        }
    }

    private static ResolvedMethodLikeDeclaration resolveNode(Node node) {
        if (node instanceof MethodCallExpr call) {
            return call.resolve();
        }
        if (node instanceof ObjectCreationExpr creation) {
            return creation.resolve();
        }
        throw new IllegalArgumentException("not a call node: " + node);
    }

    private static List<CallEdge> edgesFor(int line, String text, ResolvedMethodLikeDeclaration declaration,
                                            ProjectMethodIds methodIds, ProjectInterfaceIndex interfaceIndex,
                                            Map<String, TypeDeclaration<?>> declarationsByFqn) {
        Optional<Node> ast = declaration.toAst();
        if (ast.isEmpty() || !(ast.get() instanceof MethodDeclaration || ast.get() instanceof ConstructorDeclaration)) {
            // Bytecode/reflection-backed (a JDK class, or a dependency jar without its sources attached
            // here): a real external method, not "unresolved" - resolve() succeeded.
            return List.of(new CallEdge(line, text, null, safeQualifiedSignature(declaration), true, false));
        }

        Node declarationNode = ast.get();
        String methodId = methodIds.idFor(declarationNode);
        List<CallEdge> edges = new ArrayList<>();
        edges.add(new CallEdge(line, text, methodId, null, true, false));

        if (declarationNode instanceof MethodDeclaration methodDeclaration && methodDeclaration.getBody().isEmpty()
            && isInterface(declaration)) {
            addInterfaceImplementationEdge(line, text, methodDeclaration, declaration, methodIds, interfaceIndex, declarationsByFqn, edges);
        }
        return edges;
    }

    /**
     * T13 step 3: the call resolved to an abstract method of a project interface with no body here
     * (e.g. a Spring Data repository method is never abstract-with-no-impl in project sources, since
     * Spring Data repositories are external interfaces - this only fires for a project-declared
     * interface). If the project has exactly one class implementing it, also add an edge straight to
     * that implementation's matching method, marked {@code via_interface=true}.
     */
    private static void addInterfaceImplementationEdge(int line, String text, MethodDeclaration interfaceMethod,
                                                         ResolvedMethodLikeDeclaration declaration, ProjectMethodIds methodIds,
                                                         ProjectInterfaceIndex interfaceIndex, Map<String, TypeDeclaration<?>> declarationsByFqn,
                                                         List<CallEdge> edges) {
        String interfaceFqn = safeQualifiedName(declaration);
        if (interfaceFqn == null) {
            return;
        }
        List<String> implementors = interfaceIndex.implementorsOf(interfaceFqn);
        if (implementors.size() != 1) {
            return;
        }
        TypeDeclaration<?> implementorType = declarationsByFqn.get(implementors.get(0));
        if (implementorType == null) {
            return;
        }
        findOverride(implementorType, interfaceMethod.getNameAsString(), interfaceMethod.getParameters().size())
            .ifPresent(override -> edges.add(new CallEdge(line, text, methodIds.idFor(override), null, true, true)));
    }

    private static Optional<MethodDeclaration> findOverride(TypeDeclaration<?> implementorType, String name, int parameterCount) {
        return implementorType.getMethodsByName(name).stream()
            .filter(m -> m.getParameters().size() == parameterCount && m.getBody().isPresent())
            .findFirst();
    }

    private static boolean isInterface(ResolvedMethodLikeDeclaration declaration) {
        try {
            return declaration.declaringType().isInterface();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String safeQualifiedName(ResolvedMethodLikeDeclaration declaration) {
        try {
            return declaration.declaringType().getQualifiedName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeQualifiedSignature(ResolvedMethodLikeDeclaration declaration) {
        try {
            return declaration.getQualifiedSignature();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
