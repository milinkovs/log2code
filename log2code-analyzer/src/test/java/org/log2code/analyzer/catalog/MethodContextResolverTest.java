package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

/**
 * 0.8: the enclosing method-like unit of a log statement. Uses a marker call {@code marker()} in place
 * of a real log call - {@link MethodContextResolver} only needs any {@link Node}, matching how it is
 * invoked in production with a {@code LogCall.node()}.
 */
class MethodContextResolverTest {

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private static Node markerCall(String source) {
        CompilationUnit unit = StaticJavaParser.parse(source);
        return unit.findAll(MethodCallExpr.class).stream()
            .filter(call -> call.getNameAsString().equals("marker"))
            .<Node>map(call -> call)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no marker() call found in:\n" + source));
    }

    @Test
    void plainMethod() {
        Node node = markerCall("""
            class Fixture {
                void run(int id) {
                    marker();
                }
            }
            """);
        MethodContext ctx = MethodContextResolver.resolve(node);
        assertThat(ctx.methodName()).isEqualTo("run");
        assertThat(ctx.methodSignature()).isEqualTo("run(int)");
        assertThat(ctx.methodStartLine()).isEqualTo(2);
        assertThat(ctx.methodEndLine()).isEqualTo(4);
    }

    @Test
    void constructor() {
        Node node = markerCall("""
            class Fixture {
                Fixture(String name) {
                    marker();
                }
            }
            """);
        MethodContext ctx = MethodContextResolver.resolve(node);
        assertThat(ctx.methodName()).isEqualTo("<init>");
        assertThat(ctx.methodSignature()).isEqualTo("<init>(String)");
    }

    /**
     * A record's compact canonical constructor has no explicit parameter list in source - its
     * signature must fall back to the record's own components (real-world case found analyzing
     * {@code chaos-monkey-spring-boot} 3.1.0 in T14: {@code RequestAssaultAdapter}'s compact
     * constructor logs a warning and used to crash {@code MethodContextResolver} entirely, since
     * {@code CompactConstructorDeclaration} is not a {@code ConstructorDeclaration}).
     */
    @Test
    void recordCompactConstructor() {
        Node node = markerCall("""
            record Fixture(String rawName) {
                Fixture {
                    marker();
                }
            }
            """);
        MethodContext ctx = MethodContextResolver.resolve(node);
        assertThat(ctx.methodName()).isEqualTo("<init>");
        assertThat(ctx.methodSignature()).isEqualTo("<init>(String)");
    }

    @Test
    void staticInitializerBlock() {
        Node node = markerCall("""
            class Fixture {
                static {
                    marker();
                }
            }
            """);
        MethodContext ctx = MethodContextResolver.resolve(node);
        assertThat(ctx.methodName()).isEqualTo("<clinit>");
        assertThat(ctx.methodSignature()).isEqualTo("<clinit>()");
    }

    @Test
    void instanceInitializerBlock() {
        Node node = markerCall("""
            class Fixture {
                {
                    marker();
                }
            }
            """);
        MethodContext ctx = MethodContextResolver.resolve(node);
        assertThat(ctx.methodName()).isEqualTo("<instanceinit>");
        assertThat(ctx.methodSignature()).isEqualTo("<instanceinit>()");
    }

    @Test
    void fieldInitializerLambdaHasNoEnclosingMethod() {
        Node node = markerCall("""
            import java.util.function.Supplier;
            class Fixture {
                private static final Supplier<String> GREETING = () -> {
                    marker();
                    return "hi";
                };
            }
            """);
        MethodContext ctx = MethodContextResolver.resolve(node);
        assertThat(ctx.methodName()).isEqualTo("<field:GREETING>");
        assertThat(ctx.methodSignature()).isEqualTo("<field:GREETING>()");
    }

    @Test
    void lambdaInsideMethodBelongsToEnclosingMethod() {
        Node node = markerCall("""
            import java.util.function.Supplier;
            class Fixture {
                void run() {
                    Supplier<String> s = () -> {
                        marker();
                        return "hi";
                    };
                }
            }
            """);
        MethodContext ctx = MethodContextResolver.resolve(node);
        assertThat(ctx.methodName()).isEqualTo("run");
        assertThat(ctx.methodSignature()).isEqualTo("run()");
    }
}
