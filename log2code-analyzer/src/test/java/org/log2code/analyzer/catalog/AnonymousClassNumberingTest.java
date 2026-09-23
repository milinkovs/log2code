package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 0.8: anonymous classes are numbered per nearest enclosing named type ({@code javac}'s own scoping), in source order. */
class AnonymousClassNumberingTest {

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    @Test
    void twoAnonymousClassesInTheSameTypeAreNumberedInSourceOrder() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Outer {
                void run() {
                    Runnable a = new Runnable() { public void run() {} };
                    Runnable b = new Runnable() { public void run() {} };
                }
            }
            """);
        List<ObjectCreationExpr> anon = unit.findAll(ObjectCreationExpr.class);
        Map<Node, Integer> numbers = AnonymousClassNumbering.compute(unit);
        assertThat(numbers.get(anon.get(0))).isEqualTo(1);
        assertThat(numbers.get(anon.get(1))).isEqualTo(2);
    }

    @Test
    void nestedNamedTypeHasItsOwnIndependentCounter() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Outer {
                void run() {
                    Runnable a = new Runnable() { public void run() {} };
                }
                static class Inner {
                    void run() {
                        Runnable b = new Runnable() { public void run() {} };
                    }
                }
            }
            """);
        List<ObjectCreationExpr> anon = unit.findAll(ObjectCreationExpr.class);
        Map<Node, Integer> numbers = AnonymousClassNumbering.compute(unit);
        assertThat(numbers.get(anon.get(0))).isEqualTo(1);
        assertThat(numbers.get(anon.get(1))).isEqualTo(1);
    }

    @Test
    void anonymousInsideAnonymousSharesTheOwningNamedTypeCounter() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Outer {
                void run() {
                    Runnable a = new Runnable() {
                        public void run() {
                            Runnable inner = new Runnable() { public void run() {} };
                        }
                    };
                }
            }
            """);
        List<ObjectCreationExpr> anon = unit.findAll(ObjectCreationExpr.class);
        Map<Node, Integer> numbers = AnonymousClassNumbering.compute(unit);
        assertThat(numbers.get(anon.get(0))).isEqualTo(1); // outer anon class
        assertThat(numbers.get(anon.get(1))).isEqualTo(2); // nested anon class, same owner (Outer)
    }

    @Test
    void nonAnonymousObjectCreationIsNotNumbered() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Outer {
                void run() {
                    String plain = new String("x");
                }
            }
            """);
        Map<Node, Integer> numbers = AnonymousClassNumbering.compute(unit);
        assertThat(numbers).isEmpty();
    }
}
