package org.log2code.analyzer.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.analyzer.modules.ModuleScanner;
import org.log2code.core.model.CallEdge;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.MethodInfo;
import org.log2code.core.model.ModuleInfo;

/**
 * Against {@code src/test/resources/fixtures/graph-project/}: a known graph shape (T13's own testing
 * note) - A -> B -> C, an interface with exactly one implementation, recursion, overloaded methods, a
 * constructor call and a static call. No dependency jars are needed: every call in this fixture targets
 * either a project method or plain JDK/Object behaviour, resolvable via {@code
 * ReflectionTypeSolver(jreOnly=true)} alone.
 */
class ProjectMethodGraphBuilderTest {

    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "graph-project", "v1");
    private static final String PKG = "org.log2code.fixture.graph.";

    private final Path projectRoot = fixtureRoot();
    private final List<ModuleInfo> modules = new ModuleScanner().scan(projectRoot, List.of(), List.of());
    private final List<MethodInfo> methods =
        ProjectMethodGraphBuilder.build(projectRoot, modules, CODE_UNIT, Map.of()).methods();

    @Test
    void findsTheSingleModule() {
        assertThat(modules).extracting(ModuleInfo::module).containsExactly("module-a");
    }

    @Test
    void chainAToBToCResolvesEachHop() {
        MethodInfo methodA = find(PKG + "GraphA", "methodA()");
        MethodInfo methodB = find(PKG + "GraphB", "methodB()");
        MethodInfo methodC = find(PKG + "GraphC", "methodC()");
        MethodInfo ctorC = find(PKG + "GraphC", "<init>(String)");

        assertThat(methodA.calls()).hasSize(1);
        assertThat(methodA.calls().get(0).targetMethodId()).isEqualTo(methodB.methodId());
        assertThat(methodA.calls().get(0).resolved()).isTrue();
        assertThat(methodA.calledBy()).isEmpty(); // nothing in the fixture calls methodA (AC2's "REST entry" shape)

        assertThat(methodB.calls()).hasSize(2);
        assertThat(methodB.calls().get(0).targetMethodId()).isEqualTo(ctorC.methodId());
        assertThat(methodB.calls().get(1).targetMethodId()).isEqualTo(methodC.methodId());
        assertThat(methodB.calledBy()).extracting(CallerRef::methodId).containsExactly(methodA.methodId());
        assertThat(methodB.callerCount()).isEqualTo(1);

        assertThat(methodC.calls()).isEmpty();
        assertThat(methodC.calledBy()).extracting(CallerRef::methodId).containsExactly(methodB.methodId());
        assertThat(methodC.annotations()).contains("Deprecated");

        assertThat(ctorC.calledBy()).extracting(CallerRef::methodId).containsExactly(methodB.methodId());
    }

    @Test
    void interfaceWithExactlyOneImplementationGetsAnAdditionalEdge() {
        MethodInfo interfaceMethod = find(PKG + "Greeter", "greet(String)");
        MethodInfo implMethod = find(PKG + "GreeterImpl", "greet(String)");
        MethodInfo caller = find(PKG + "GreeterCaller", "callGreeter()");

        assertThat(interfaceMethod.calls()).isEmpty(); // no body: an abstract interface method
        assertThat(interfaceMethod.hasLogStatements()).isFalse();

        // new GreeterImpl() (implicit constructor, no AST -> external, targetFqn set instead) plus
        // g.greet(...) resolved twice: once to the interface method (as actually written) and once,
        // via_interface, to the sole implementation.
        assertThat(caller.calls()).hasSize(3);
        List<CallEdge> greetEdges = caller.calls().stream().filter(e -> e.targetMethodId() != null).toList();
        assertThat(greetEdges).hasSize(2);
        assertThat(greetEdges).anySatisfy(e -> {
            assertThat(e.targetMethodId()).isEqualTo(interfaceMethod.methodId());
            assertThat(e.viaInterface()).isFalse();
        });
        assertThat(greetEdges).anySatisfy(e -> {
            assertThat(e.targetMethodId()).isEqualTo(implMethod.methodId());
            assertThat(e.viaInterface()).isTrue();
        });

        assertThat(interfaceMethod.calledBy()).extracting(CallerRef::methodId).containsExactly(caller.methodId());
        assertThat(implMethod.calledBy()).extracting(CallerRef::methodId).containsExactly(caller.methodId());
    }

    @Test
    void recursionTargetsItsOwnMethodId() {
        MethodInfo factorial = find(PKG + "Recursive", "factorial(int)");

        assertThat(factorial.calls()).hasSize(1);
        assertThat(factorial.calls().get(0).targetMethodId()).isEqualTo(factorial.methodId());
        assertThat(factorial.calledBy()).extracting(CallerRef::methodId).containsExactly(factorial.methodId());
    }

    @Test
    void overloadsResolveToTheMatchingSignature() {
        MethodInfo describeInt = find(PKG + "Overloaded", "describe(int)");
        MethodInfo describeString = find(PKG + "Overloaded", "describe(String)");
        MethodInfo describeBoth = find(PKG + "Overloaded", "describeBoth()");

        assertThat(describeBoth.calls()).hasSize(2);
        assertThat(describeBoth.calls().get(0).targetMethodId()).isEqualTo(describeInt.methodId());
        assertThat(describeBoth.calls().get(1).targetMethodId()).isEqualTo(describeString.methodId());
        assertThat(describeInt.methodId()).isNotEqualTo(describeString.methodId());
    }

    @Test
    void staticCallResolvesToTheStaticMethod() {
        MethodInfo staticHelper = find(PKG + "GraphUtil", "staticHelper()");
        MethodInfo caller = find(PKG + "StaticCaller", "callStatic()");
        MethodInfo utilCtor = find(PKG + "GraphUtil", "<init>()");

        assertThat(caller.calls()).hasSize(1);
        assertThat(caller.calls().get(0).targetMethodId()).isEqualTo(staticHelper.methodId());
        assertThat(staticHelper.calledBy()).extracting(CallerRef::methodId).containsExactly(caller.methodId());
        assertThat(utilCtor.calledBy()).isEmpty(); // GraphUtil's private constructor is never called
    }

    private MethodInfo find(String classFqn, String methodSignature) {
        return methods.stream()
            .filter(m -> m.classFqn().equals(classFqn) && m.methodSignature().equals(methodSignature))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no method " + classFqn + "#" + methodSignature + " in " + methods));
    }

    private static Path fixtureRoot() {
        try {
            return Paths.get(ProjectMethodGraphBuilderTest.class.getResource("/fixtures/graph-project/pom.xml").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
