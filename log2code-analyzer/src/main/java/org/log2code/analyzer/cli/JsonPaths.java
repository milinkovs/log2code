package org.log2code.analyzer.cli;

import java.nio.file.Path;
import org.log2code.core.model.CodeUnit;

/**
 * Resolves the {@code --out json|both} directory for one code unit's version. Windows workaround
 * (0.13): a dependency's {@code code_unit.name} is {@code groupId:artifactId} (T14 step 1), and NTFS
 * rejects {@code :} inside a path segment (it is reserved for alternate-data-stream syntax), so the
 * colon becomes a directory separator here - {@code data/work/analyzer/<groupId>/<artifactId>/<version>/}
 * - instead of one invalid segment. A project's name has no colon, so this is a no-op for it.
 */
final class JsonPaths {

    private JsonPaths() {
    }

    static Path forCodeUnit(Path jsonDir, CodeUnit codeUnit) {
        Path dir = jsonDir;
        for (String segment : codeUnit.name().split(":")) {
            dir = dir.resolve(segment);
        }
        return dir.resolve(codeUnit.version());
    }
}
