package org.log2code.analyzer.catalog;

import com.github.javaparser.ast.body.TypeDeclaration;
import org.log2code.analyzer.logging.ClassFqns;
import org.log2code.analyzer.logging.ClassInfo;
import org.log2code.analyzer.logging.TypeIndex;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.TypeInfo;

/** Builds one {@link TypeInfo} (0.7) for every named type declaration (top-level or nested) in a file. */
public final class TypeInfoBuilder {

    private TypeInfoBuilder() {
    }

    public static TypeInfo build(TypeDeclaration<?> type, TypeIndex typeIndex, FileInfo file, CodeUnit codeUnit) {
        String classFqn = ClassFqns.of(type);
        String classBinary = ClassFqns.binaryOf(type);
        ClassInfo info = typeIndex.classesByFqn().get(classFqn);
        String typeId = StableIds.typeId(codeUnit.name(), codeUnit.version(), classFqn);
        return new TypeInfo(typeId, codeUnit, file.module(), file.filePath(), classFqn, classBinary,
            info.superclassFqn(), info.interfaceFqns(), info.kind());
    }
}
