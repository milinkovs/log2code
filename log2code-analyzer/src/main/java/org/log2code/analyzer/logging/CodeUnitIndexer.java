package org.log2code.analyzer.logging;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pass 1 (T08 step 3): builds a {@link TypeIndex} for a whole code unit from its parsed sources. Runs
 * in two passes of its own so that a class declared in one file can resolve a superclass/logger field
 * type declared in another file of the same code unit: first every named type's FQN is collected
 * (1a), then superclass/interfaces/logger fields are resolved against that now-complete set (1b/1c).
 */
final class CodeUnitIndexer {

    private CodeUnitIndexer() {
    }

    static TypeIndex index(List<CompilationUnit> units) {
        Map<String, TypeDeclaration<?>> declarationsByFqn = new LinkedHashMap<>();
        Map<String, TypeNameResolver> resolverByFqn = new LinkedHashMap<>();
        for (CompilationUnit unit : units) {
            TypeNameResolver resolver = new TypeNameResolver(unit);
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                String fqn = ClassFqns.of(type);
                declarationsByFqn.put(fqn, type);
                resolverByFqn.put(fqn, resolver);
            }
        }
        Set<String> allFqns = declarationsByFqn.keySet();

        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        for (Map.Entry<String, TypeDeclaration<?>> entry : declarationsByFqn.entrySet()) {
            String fqn = entry.getKey();
            TypeDeclaration<?> type = entry.getValue();
            TypeNameResolver resolver = resolverByFqn.get(fqn);

            String superclassFqn = resolveSuperclass(type, resolver, allFqns);
            List<String> interfaceFqns = resolveInterfaces(type, resolver, allFqns);
            Map<String, LoggerFieldInfo> loggerFields = loggerFields(type, resolver);

            classes.put(fqn, new ClassInfo(fqn, superclassFqn, interfaceFqns, loggerFields));
        }
        return new TypeIndex(Map.copyOf(classes));
    }

    private static String resolveSuperclass(TypeDeclaration<?> type, TypeNameResolver resolver, Set<String> allFqns) {
        if (type instanceof ClassOrInterfaceDeclaration coid && !coid.isInterface() && !coid.getExtendedTypes().isEmpty()) {
            String raw = TypeNameResolver.rawName(coid.getExtendedTypes(0));
            return resolver.resolve(raw, allFqns).orElse(null);
        }
        return null;
    }

    private static List<String> resolveInterfaces(TypeDeclaration<?> type, TypeNameResolver resolver, Set<String> allFqns) {
        if (!(type instanceof ClassOrInterfaceDeclaration coid)) {
            return List.of();
        }
        List<ClassOrInterfaceType> raw = coid.isInterface() ? coid.getExtendedTypes() : coid.getImplementedTypes();
        List<String> resolved = new ArrayList<>();
        for (ClassOrInterfaceType t : raw) {
            resolver.resolve(TypeNameResolver.rawName(t), allFqns).ifPresent(resolved::add);
        }
        return List.copyOf(resolved);
    }

    private static Map<String, LoggerFieldInfo> loggerFields(TypeDeclaration<?> type, TypeNameResolver resolver) {
        Map<String, LoggerFieldInfo> fields = new LinkedHashMap<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (!(member instanceof FieldDeclaration fieldDecl)) {
                continue;
            }
            for (VariableDeclarator variable : fieldDecl.getVariables()) {
                Type declaredType = variable.getType();
                if (!(declaredType instanceof ClassOrInterfaceType coit)) {
                    continue;
                }
                Optional<LoggerTypeSpec> spec =
                    resolver.resolve(TypeNameResolver.rawName(coit), LoggingApiRegistry.knownFqns())
                        .flatMap(LoggingApiRegistry::forType);
                if (spec.isEmpty()) {
                    continue;
                }
                LoggerInitializer.Resolved initializer = LoggerInitializer.resolve(variable, type, resolver);
                fields.put(variable.getNameAsString(),
                    new LoggerFieldInfo(spec.get().api(), initializer.loggerName(), initializer.loggerNameKind()));
            }
        }
        return Map.copyOf(fields);
    }
}
