package org.log2code.analyzer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

/** 0.8's {@code method_signature} form: {@code ime(T1,T2)}, no generics/package, {@code ...} to {@code []}. */
class MethodSignaturesTest {

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private static CompilationUnit parse(String body) {
        return StaticJavaParser.parse("""
            package org.log2code.fixture.catalog;
            import java.util.List;
            import java.util.Map;
            class Fixture {
                %s
            }
            """.formatted(body));
    }

    private static String methodSignature(String methodSource) {
        CompilationUnit unit = parse(methodSource);
        MethodDeclaration method = unit.findFirst(MethodDeclaration.class).orElseThrow();
        return MethodSignatures.of(method);
    }

    @Test
    void noParameters() {
        assertThat(methodSignature("void run() {}")).isEqualTo("run()");
    }

    @Test
    void primitiveParameters() {
        assertThat(methodSignature("void run(int a, boolean b) {}")).isEqualTo("run(int,boolean)");
    }

    @Test
    void simpleClassParameter() {
        assertThat(methodSignature("void run(String s) {}")).isEqualTo("run(String)");
    }

    @Test
    void genericParameterDropsTypeArguments() {
        assertThat(methodSignature("void run(List<String> xs) {}")).isEqualTo("run(List)");
    }

    @Test
    void fullyQualifiedGenericParameterDropsPackageAndGenerics() {
        assertThat(methodSignature("void run(java.util.Map<String,Integer> m) {}")).isEqualTo("run(Map)");
    }

    @Test
    void arrayParameterKeepsDimensions() {
        assertThat(methodSignature("void run(String[] xs, int[][] grid) {}")).isEqualTo("run(String[],int[][])");
    }

    @Test
    void varargsParameterBecomesArray() {
        assertThat(methodSignature("void run(String... xs) {}")).isEqualTo("run(String[])");
    }

    @Test
    void constructorSignatureUsesInitMarker() {
        CompilationUnit unit = parse("Fixture(int id, String name) {}");
        ConstructorDeclaration ctor = unit.findFirst(ConstructorDeclaration.class).orElseThrow();
        assertThat(MethodSignatures.of(ctor)).isEqualTo("<init>(int,String)");
    }

    /** The compact constructor itself has no parameter list in source - it borrows the record's components. */
    @Test
    void compactConstructorSignatureUsesRecordComponents() {
        CompilationUnit unit = StaticJavaParser.parse("""
            package org.log2code.fixture.catalog;
            record Fixture(int id, String name) {
                Fixture {
                }
            }
            """);
        CompactConstructorDeclaration ctor = unit.findFirst(CompactConstructorDeclaration.class).orElseThrow();
        assertThat(MethodSignatures.of(ctor)).isEqualTo("<init>(int,String)");
    }

    @Test
    void staticInitializerHasNoParameters() {
        assertThat(MethodSignatures.staticInitializer()).isEqualTo("<clinit>()");
    }

    @Test
    void fieldInitializerNamesTheField() {
        assertThat(MethodSignatures.fieldInitializer("counter")).isEqualTo("<field:counter>()");
    }
}
