package org.log2code.analyzer.template;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.log2code.analyzer.logging.ClassFqns;

/**
 * Every {@code static final String} field's initializer expression, across one code unit, keyed by
 * declaring class FQN and field name (T09 rule 3, step 3: "polje dostupno preko importa u istom
 * code unit-u" - a field outside this index is, by construction, outside the code unit being
 * analyzed, so a reference to it cannot resolve). Public: built once per code unit alongside
 * {@link org.log2code.analyzer.logging.LogCallDetector#detectAll}, by the same caller (T10's future
 * catalog assembly, and {@code ProjectCommand}'s {@code --list-calls} debug output today).
 */
public final class ConstantIndex {

    private final Map<String, Map<String, Expression>> fieldsByClass;

    private ConstantIndex(Map<String, Map<String, Expression>> fieldsByClass) {
        this.fieldsByClass = fieldsByClass;
    }

    public static ConstantIndex build(List<CompilationUnit> units) {
        Map<String, Map<String, Expression>> byClass = new LinkedHashMap<>();
        for (CompilationUnit unit : units) {
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                Map<String, Expression> fields = new LinkedHashMap<>();
                for (FieldDeclaration field : type.getFields()) {
                    if (!field.isStatic() || !field.isFinal()) {
                        continue;
                    }
                    for (VariableDeclarator variable : field.getVariables()) {
                        if (!isStringType(variable.getType()) || variable.getInitializer().isEmpty()) {
                            continue;
                        }
                        fields.put(variable.getNameAsString(), variable.getInitializer().get());
                    }
                }
                byClass.put(ClassFqns.of(type), fields);
            }
        }
        return new ConstantIndex(Map.copyOf(byClass));
    }

    Optional<Expression> field(String classFqn, String name) {
        Map<String, Expression> fields = fieldsByClass.get(classFqn);
        return fields == null ? Optional.empty() : Optional.ofNullable(fields.get(name));
    }

    boolean hasClass(String classFqn) {
        return fieldsByClass.containsKey(classFqn);
    }

    private static boolean isStringType(Type type) {
        return type.isClassOrInterfaceType() && "String".equals(type.asClassOrInterfaceType().getNameAsString());
    }
}
