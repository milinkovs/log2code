package org.log2code.core.model;

import java.util.List;

/** One type (document {@code log2code-types}, {@code _id = typeId}). */
public record TypeInfo(
    String typeId,
    CodeUnit codeUnit,
    String module,
    String filePath,
    String classFqn,
    String classBinary,
    String superclassFqn,
    List<String> interfaces,
    String kind
) {
}
