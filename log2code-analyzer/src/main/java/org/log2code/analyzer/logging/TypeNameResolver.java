package org.log2code.analyzer.logging;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a type name as it is written in one compilation unit ({@code "Logger"},
 * {@code "org.slf4j.Logger"}, {@code "System.Logger"}...) to a fully-qualified name, against a given
 * set of FQNs of interest (step 2: "Tip se razrešava preko importa, FQN-a u deklaraciji, istog paketa
 * ili statičkog importa"). A static-imported factory <em>method</em> (as opposed to the logger type
 * itself) is not resolved here; {@link LoggerInitializer} matches those by method name alone.
 */
final class TypeNameResolver {

    private final String packageName;
    private final Map<String, String> singleTypeImports;

    TypeNameResolver(CompilationUnit unit) {
        this.packageName = unit.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        this.singleTypeImports = new LinkedHashMap<>();
        for (ImportDeclaration importDecl : unit.getImports()) {
            if (importDecl.isAsterisk()) {
                continue;
            }
            String fqn = importDecl.getNameAsString();
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            singleTypeImports.put(simpleName, fqn);
        }
    }

    /** Raw dotted name of a syntactic type reference (scope segments + name), ignoring type arguments. */
    static String rawName(ClassOrInterfaceType type) {
        StringBuilder name = new StringBuilder(type.getNameAsString());
        Optional<ClassOrInterfaceType> scope = type.getScope();
        while (scope.isPresent()) {
            name.insert(0, scope.get().getNameAsString() + ".");
            scope = scope.get().getScope();
        }
        return name.toString();
    }

    /**
     * Resolves {@code rawTypeName} against {@code knownFqns}: already-qualified and already known: used
     * as-is; otherwise the leading segment is resolved via a single-type import, the implicit
     * {@code java.lang} package (so plain {@code System.Logger} resolves without an import), or the
     * compilation unit's own package, and the rest of the name is appended back.
     */
    Optional<String> resolve(String rawTypeName, Set<String> knownFqns) {
        if (knownFqns.contains(rawTypeName)) {
            return Optional.of(rawTypeName);
        }
        int dot = rawTypeName.indexOf('.');
        String head = dot < 0 ? rawTypeName : rawTypeName.substring(0, dot);
        String tail = dot < 0 ? "" : rawTypeName.substring(dot);

        String imported = singleTypeImports.get(head);
        if (imported != null && knownFqns.contains(imported + tail)) {
            return Optional.of(imported + tail);
        }
        if ("System".equals(head) && knownFqns.contains("java.lang.System" + tail)) {
            return Optional.of("java.lang.System" + tail);
        }
        if (!packageName.isEmpty() && knownFqns.contains(packageName + "." + rawTypeName)) {
            return Optional.of(packageName + "." + rawTypeName);
        }
        return Optional.empty();
    }

    /**
     * Best-effort resolution against imports/package only, with no restriction to a known FQN set:
     * used for a logger's {@code X.class} argument, which may name any class, not just one of our
     * known logger types. Always returns a value (falls back to the raw name in the default package).
     */
    String resolveAny(String rawTypeName) {
        if (rawTypeName.indexOf('.') >= 0) {
            return rawTypeName;
        }
        String imported = singleTypeImports.get(rawTypeName);
        if (imported != null) {
            return imported;
        }
        if (!packageName.isEmpty()) {
            return packageName + "." + rawTypeName;
        }
        return rawTypeName;
    }
}
