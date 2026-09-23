package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 0.8: {@code class_fqn}/{@code class_binary} of a log statement's enclosing class. A nested named
 * class is {@code Outer.Inner}/{@code Outer$Inner}; an anonymous class has no dotted form, so both
 * fields become its estimated binary name, {@code <owner-binary>$N} (ADR-010).
 */
class ClassContextResolverTest {

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private static ClassContext resolve(String source) {
        CompilationUnit unit = StaticJavaParser.parse(source);
        Node node = unit.findAll(MethodCallExpr.class).stream()
            .filter(call -> call.getNameAsString().equals("marker"))
            .<Node>map(call -> call)
            .findFirst()
            .orElseThrow();
        Map<Node, Integer> anonymousClassNumbers = AnonymousClassNumbering.compute(unit);
        return ClassContextResolver.resolve(node, anonymousClassNumbers);
    }

    @Test
    void topLevelClass() {
        ClassContext ctx = resolve("""
            package a.b;
            class Outer {
                void run() {
                    marker();
                }
            }
            """);
        assertThat(ctx.classFqn()).isEqualTo("a.b.Outer");
        assertThat(ctx.classBinary()).isEqualTo("a.b.Outer");
    }

    @Test
    void nestedNamedClass() {
        ClassContext ctx = resolve("""
            package a.b;
            class Outer {
                static class Inner {
                    void run() {
                        marker();
                    }
                }
            }
            """);
        assertThat(ctx.classFqn()).isEqualTo("a.b.Outer.Inner");
        assertThat(ctx.classBinary()).isEqualTo("a.b.Outer$Inner");
    }

    @Test
    void defaultPackageHasNoLeadingDot() {
        ClassContext ctx = resolve("""
            class Outer {
                void run() {
                    marker();
                }
            }
            """);
        assertThat(ctx.classFqn()).isEqualTo("Outer");
        assertThat(ctx.classBinary()).isEqualTo("Outer");
    }

    @Test
    void anonymousClassInTopLevelType() {
        ClassContext ctx = resolve("""
            package a.b;
            class Outer {
                void run() {
                    Runnable r = new Runnable() {
                        public void run() {
                            marker();
                        }
                    };
                }
            }
            """);
        assertThat(ctx.classFqn()).isEqualTo("a.b.Outer$1");
        assertThat(ctx.classBinary()).isEqualTo("a.b.Outer$1");
    }

    @Test
    void secondAnonymousClassGetsTheNextNumber() {
        ClassContext ctx = resolve("""
            package a.b;
            class Outer {
                void run() {
                    Runnable first = new Runnable() { public void run() { } };
                    Runnable second = new Runnable() {
                        public void run() {
                            marker();
                        }
                    };
                }
            }
            """);
        assertThat(ctx.classBinary()).isEqualTo("a.b.Outer$2");
    }

    @Test
    void anonymousClassInsideNestedNamedTypeUsesThatTypesOwnBinaryName() {
        ClassContext ctx = resolve("""
            package a.b;
            class Outer {
                static class Inner {
                    void run() {
                        Runnable r = new Runnable() {
                            public void run() {
                                marker();
                            }
                        };
                    }
                }
            }
            """);
        assertThat(ctx.classBinary()).isEqualTo("a.b.Outer$Inner$1");
    }
}
