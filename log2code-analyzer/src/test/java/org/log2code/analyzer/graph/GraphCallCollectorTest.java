package org.log2code.analyzer.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure AST traversal, no symbol solver needed (T13 step 2): every construct here is syntactically
 * valid, whether or not it would actually compile.
 */
class GraphCallCollectorTest {

    @Test
    void collectsPlainMethodCallsAndObjectCreations() {
        List<Node> calls = collect("a(); new Foo(); b();");
        assertThat(calls).hasSize(3);
    }

    @Test
    void descendsIntoLambdaBodies() {
        List<Node> calls = collect("java.util.function.Supplier<Object> s = () -> inner();");
        assertThat(calls).hasSize(1);
    }

    @Test
    void excludesLocalClassMembers() {
        List<Node> calls = collect("class Local { void x() { hiddenInLocal(); } } visible();");
        assertThat(calls).hasSize(1);
    }

    @Test
    void anonymousClassBodyIsExcludedButItsArgumentsAreNot() {
        List<Node> calls = collect("Object o = new Wrapper(argCall()) { void y() { hiddenInAnon(); } };");
        // the "new Wrapper(...) {...}" creation itself, plus its eagerly-evaluated argument
        assertThat(calls).hasSize(2);
    }

    @Test
    void chainedCallsAreAllCollected() {
        List<Node> calls = collect("a().b().c();");
        assertThat(calls).hasSize(3);
    }

    private static List<Node> collect(String statements) {
        CompilationUnit unit = StaticJavaParser.parse("class X { void m() { " + statements + " } }");
        BlockStmt body = unit.findFirst(MethodDeclaration.class).orElseThrow().getBody().orElseThrow();
        return GraphCallCollector.collect(body);
    }
}
