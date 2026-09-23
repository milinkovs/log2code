package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import java.util.stream.Collectors;

/**
 * Formats the 0.8 {@code method_signature} form ({@code ime(T1,T2)}): parameter types are written as
 * in the source but without generics or package qualification ({@code List<Owner>} to {@code List},
 * {@code java.util.Map<K,V>} to {@code Map}, {@code String...} to {@code String[]}).
 */
public final class MethodSignatures {

    private MethodSignatures() {
    }

    public static String of(MethodDeclaration method) {
        return signature(method.getNameAsString(), method.getParameters());
    }

    public static String of(ConstructorDeclaration constructor) {
        return signature("<init>", constructor.getParameters());
    }

    /**
     * A record's compact canonical constructor ({@code private Foo { ... }}) has no explicit parameter
     * list in source - its parameters are implicitly the record's own components, in declaration order.
     */
    public static String of(CompactConstructorDeclaration constructor) {
        NodeList<Parameter> components = constructor.getParentNode()
            .filter(RecordDeclaration.class::isInstance)
            .map(RecordDeclaration.class::cast)
            .map(RecordDeclaration::getParameters)
            .orElseGet(NodeList::new);
        return signature("<init>", components);
    }

    /** {@code <clinit>()}: a static initializer block takes no parameters. */
    public static String staticInitializer() {
        return "<clinit>()";
    }

    /** {@code <field:name>()}: a field initializer's log call belongs to that field, not a method. */
    public static String fieldInitializer(String fieldName) {
        return "<field:" + fieldName + ">()";
    }

    private static String signature(String name, Iterable<Parameter> parameters) {
        String params = java.util.stream.StreamSupport.stream(parameters.spliterator(), false)
            .map(MethodSignatures::formatParameter)
            .collect(Collectors.joining(","));
        return name + "(" + params + ")";
    }

    private static String formatParameter(Parameter parameter) {
        String type = formatType(parameter.getType());
        return parameter.isVarArgs() ? type + "[]" : type;
    }

    /** Strips generics and package qualification; keeps array dimensions. */
    static String formatType(Type type) {
        if (type instanceof ArrayType arrayType) {
            return formatType(arrayType.getComponentType()) + "[]";
        }
        if (type instanceof PrimitiveType primitiveType) {
            return primitiveType.asString();
        }
        // ClassOrInterfaceType.getNameAsString() already drops scope/package and generic arguments,
        // keeping only the simple name; UnionType/other rare parameter type forms fall back to that
        // simple-name stripping via a best-effort regex on the source text.
        if (type instanceof com.github.javaparser.ast.type.ClassOrInterfaceType classType) {
            return classType.getNameAsString();
        }
        return type.asString().replaceAll("<[^>]*>", "").replaceAll("^.*\\.", "");
    }
}
