package org.log2code.ingester.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.TypeInfo;

/** T19 step 4: the superclass chain {@link CatalogIndex#byLogger} needs for 0.10 step 2's {@code get_class}. */
class TypeHierarchyTest {

    private static final CodeUnit CODE_UNIT = new CodeUnit(CodeUnit.TYPE_PROJECT, "p", "v1");

    @Test
    void climbsFromAConcreteSubclassUpToAnAbstractBaseClass() {
        // AbstractController -> AbstractBaseController -> ConcreteController, i.e. exactly the
        // "getClass()" scenario: a log statement in AbstractController's body observes ConcreteController
        // as the runtime logger name, and the chain from ConcreteController must reach AbstractController.
        TypeHierarchy hierarchy = TypeHierarchy.build(List.of(
            type("pkg.ConcreteController", "pkg.AbstractBaseController"),
            type("pkg.AbstractBaseController", "pkg.AbstractController"),
            type("pkg.AbstractController", null)));

        assertThat(hierarchy.ancestors("pkg.ConcreteController"))
            .containsExactly("pkg.AbstractBaseController", "pkg.AbstractController");
    }

    @Test
    void aRootClassWithNoSuperclassHasNoAncestors() {
        TypeHierarchy hierarchy = TypeHierarchy.build(List.of(type("pkg.Root", null)));

        assertThat(hierarchy.ancestors("pkg.Root")).isEmpty();
    }

    @Test
    void anUnknownClassNameResolvesToAnEmptyChainInsteadOfThrowing() {
        TypeHierarchy hierarchy = TypeHierarchy.build(List.of(type("pkg.Known", null)));

        assertThat(hierarchy.ancestors("pkg.NeverSeen")).isEmpty();
        assertThat(hierarchy.ancestors(null)).isEmpty();
    }

    @Test
    void aCycleStopsInsteadOfLoopingForever() {
        // Cannot happen for a real Java class hierarchy, but the loader must not hang if types data is
        // ever inconsistent (T19 step 4: "sa zaštitom od ciklusa").
        TypeHierarchy hierarchy = TypeHierarchy.build(List.of(
            type("pkg.A", "pkg.B"),
            type("pkg.B", "pkg.A")));

        assertThat(hierarchy.ancestors("pkg.A")).containsExactly("pkg.B");
    }

    @Test
    void aChainLongerThanTwentyLevelsIsCappedAtTwenty() {
        List<TypeInfo> chain = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String superFqn = i == 24 ? null : "pkg.Level" + (i + 1);
            chain.add(type("pkg.Level" + i, superFqn));
        }

        TypeHierarchy hierarchy = TypeHierarchy.build(chain);

        assertThat(hierarchy.ancestors("pkg.Level0")).hasSize(TypeHierarchy.MAX_DEPTH);
    }

    private static TypeInfo type(String classFqn, String superclassFqn) {
        return new TypeInfo(classFqn, CODE_UNIT, "module-a", classFqn.replace('.', '/') + ".java",
            classFqn, classFqn, superclassFqn, List.of(), "class");
    }
}
