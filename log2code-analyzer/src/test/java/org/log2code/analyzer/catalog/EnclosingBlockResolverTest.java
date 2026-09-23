package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.EnclosingBlock;

/**
 * 0.7/T10 step 1: the single nearest enclosing block. Only {@code if} (condition+branch) and
 * {@code catch} (exception type as condition) populate anything beyond {@code block_kind} (ADR-010).
 */
class EnclosingBlockResolverTest {

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private static EnclosingBlock resolve(String methodBody) {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Fixture {
                void run(int id) {
                    %s
                }
            }
            """.formatted(methodBody));
        Node node = unit.findAll(MethodCallExpr.class).stream()
            .filter(call -> call.getNameAsString().equals("marker"))
            .<Node>map(call -> call)
            .findFirst()
            .orElseThrow();
        MethodContext methodContext = MethodContextResolver.resolve(node);
        return EnclosingBlockResolver.resolve(node, methodContext);
    }

    @Test
    void directlyInMethodBodyFallsBackToMethod() {
        EnclosingBlock block = resolve("marker();");
        assertThat(block.blockKind()).isEqualTo("method");
        assertThat(block.condition()).isNull();
        assertThat(block.branch()).isNull();
        assertThat(block.startLine()).isEqualTo(2);
        assertThat(block.endLine()).isEqualTo(4);
    }

    @Test
    void ifThenBranch() {
        EnclosingBlock block = resolve("""
            if (id > 0) {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("if");
        assertThat(block.condition()).isEqualTo("id > 0");
        assertThat(block.branch()).isEqualTo("then");
    }

    @Test
    void ifElseBranch() {
        EnclosingBlock block = resolve("""
            if (id > 0) {
                doSomething();
            } else {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("if");
        assertThat(block.condition()).isEqualTo("id > 0");
        assertThat(block.branch()).isEqualTo("else");
    }

    @Test
    void ifWithoutBracesStillResolves() {
        EnclosingBlock block = resolve("if (id > 0) marker();");
        assertThat(block.blockKind()).isEqualTo("if");
        assertThat(block.branch()).isEqualTo("then");
    }

    @Test
    void forLoop() {
        EnclosingBlock block = resolve("""
            for (int i = 0; i < id; i++) {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("for");
        assertThat(block.condition()).isNull();
    }

    @Test
    void foreachLoop() {
        EnclosingBlock block = resolve("""
            for (int i : new int[] {1, 2}) {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("foreach");
    }

    @Test
    void whileLoop() {
        EnclosingBlock block = resolve("""
            while (id > 0) {
                marker();
                id--;
            }
            """);
        assertThat(block.blockKind()).isEqualTo("while");
    }

    @Test
    void doWhileLoop() {
        EnclosingBlock block = resolve("""
            do {
                marker();
                id--;
            } while (id > 0);
            """);
        assertThat(block.blockKind()).isEqualTo("do");
    }

    @Test
    void switchCase() {
        EnclosingBlock block = resolve("""
            switch (id) {
                case 1 -> marker();
                default -> {
                }
            }
            """);
        assertThat(block.blockKind()).isEqualTo("switch_case");
    }

    @Test
    void tryBlock() {
        EnclosingBlock block = resolve("""
            try {
                marker();
            } catch (RuntimeException e) {
                throw e;
            }
            """);
        assertThat(block.blockKind()).isEqualTo("try");
    }

    @Test
    void catchBlockReportsExceptionType() {
        EnclosingBlock block = resolve("""
            try {
                risky();
            } catch (IllegalStateException e) {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("catch");
        assertThat(block.condition()).isEqualTo("IllegalStateException");
        assertThat(block.branch()).isNull();
    }

    @Test
    void multiCatchReportsUnionType() {
        EnclosingBlock block = resolve("""
            try {
                risky();
            } catch (IllegalStateException | IllegalArgumentException e) {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("catch");
        assertThat(block.condition()).contains("IllegalStateException").contains("IllegalArgumentException");
    }

    @Test
    void finallyBlock() {
        EnclosingBlock block = resolve("""
            try {
                risky();
            } finally {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("finally");
    }

    @Test
    void synchronizedBlock() {
        EnclosingBlock block = resolve("""
            synchronized (this) {
                marker();
            }
            """);
        assertThat(block.blockKind()).isEqualTo("synchronized");
    }

    @Test
    void lambdaBody() {
        EnclosingBlock block = resolve("""
            Runnable r = () -> {
                marker();
            };
            r.run();
            """);
        assertThat(block.blockKind()).isEqualTo("lambda");
    }

    @Test
    void nearestBlockWinsOverOuterMethod() {
        EnclosingBlock block = resolve("""
            if (id > 0) {
                for (int i = 0; i < id; i++) {
                    marker();
                }
            }
            """);
        assertThat(block.blockKind()).isEqualTo("for");
    }
}
