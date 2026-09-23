package org.log2code.analyzer.graph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import org.log2code.analyzer.catalog.ClassContext;
import org.log2code.analyzer.catalog.ClassContextResolver;
import org.log2code.analyzer.catalog.MethodSignatures;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CodeUnit;

/**
 * Computes a project {@code method_id} (0.8) for a {@link MethodDeclaration}/{@link
 * ConstructorDeclaration} node, on demand and purely structurally: {@code
 * ResolvedMethodLikeDeclaration#toAst()} (T13 step 2) can hand back a node from a completely different
 * parse of the same file than the one the graph builder's own module loop is walking - {@code
 * JavaParserTypeSolver} parses lazily, with its own internal {@code JavaParser} instance - so an
 * identity-based lookup into the builder's own method table would miss it. Recomputing {@code
 * classFqn}/{@code methodSignature} from whatever node comes back works regardless of which parse it
 * came from. Anonymous-class numbering (needed for {@code classFqn} when the declaration lives inside
 * one) is cached per {@link CompilationUnit} so a heavily-referenced file is only walked once.
 */
final class ProjectMethodIds {

    private final CodeUnit codeUnit;
    private final Map<CompilationUnit, Map<Node, Integer>> anonymousNumbersByUnit = new IdentityHashMap<>();

    ProjectMethodIds(CodeUnit codeUnit) {
        this.codeUnit = codeUnit;
    }

    /** {@code null} if {@code declarationNode} is not a method/constructor, or has no enclosing compilation unit. */
    String idFor(Node declarationNode) {
        String methodSignature = signatureOf(declarationNode);
        if (methodSignature == null) {
            return null;
        }
        Optional<CompilationUnit> unit = declarationNode.findCompilationUnit();
        if (unit.isEmpty()) {
            return null;
        }
        Map<Node, Integer> anonymousNumbers = anonymousNumbersByUnit
            .computeIfAbsent(unit.get(), org.log2code.analyzer.catalog.AnonymousClassNumbering::compute);
        ClassContext classContext = ClassContextResolver.resolve(declarationNode, anonymousNumbers);
        return StableIds.methodId(codeUnit.name(), codeUnit.version(), classContext.classFqn(), methodSignature);
    }

    private static String signatureOf(Node node) {
        if (node instanceof MethodDeclaration method) {
            return MethodSignatures.of(method);
        }
        if (node instanceof ConstructorDeclaration constructor) {
            return MethodSignatures.of(constructor);
        }
        return null;
    }
}
