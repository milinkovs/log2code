package org.log2code.analyzer.deps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.log2code.core.model.ModuleInfo;

/**
 * Top-level class FQNs of the project itself (T12 step 2: "loggeri koji pripadaju klasama projekta se
 * preskaju"). Package is read from the file's {@code package} declaration, class name from the file name;
 * this is a lightweight scan (no AST), sufficient because a logger always names a top-level class
 * ({@code LoggerFactory.getLogger(X.class)} never targets a local/anonymous class).
 */
public final class ProjectClassIndex {

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private ProjectClassIndex() {
    }

    public static Set<String> classFqns(Path projectRoot, List<ModuleInfo> modules) {
        Set<String> fqns = new TreeSet<>();
        for (ModuleInfo module : modules) {
            for (String sourceRoot : module.sourceRoots()) {
                Path root = projectRoot.resolve(module.module()).resolve(sourceRoot);
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                        fqns.add(fqnOf(file));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to scan " + root, e);
                }
            }
        }
        return fqns;
    }

    private static String fqnOf(Path javaFile) {
        String fileName = javaFile.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - ".java".length());
        String packageName = readPackage(javaFile);
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static String readPackage(Path javaFile) {
        try {
            String content = Files.readString(javaFile);
            Matcher matcher = PACKAGE_DECL.matcher(content);
            return matcher.find() ? matcher.group(1) : "";
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + javaFile, e);
        }
    }
}
