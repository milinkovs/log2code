package org.log2code.analyzer.deps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The fully-qualified class names contained in a binary jar (T12 step 2: "za svaki jedinstveni logger
 * pronađi artefakt(e) čiji binarni jar sadrži odgovarajuću klasu"). Only entry names are read (cheap:
 * no class bytes are parsed); results are cached per absolute jar path since the same dependency jar
 * (e.g. {@code spring-boot.jar}) is shared by every lite-profile module.
 */
public final class JarClassIndex {

    private static final Map<Path, List<String>> CACHE = new ConcurrentHashMap<>();

    private JarClassIndex() {
    }

    public static List<String> classFqns(Path jarPath) {
        return CACHE.computeIfAbsent(jarPath.toAbsolutePath(), JarClassIndex::read);
    }

    private static List<String> read(Path jarPath) {
        List<String> fqns = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.equals("module-info.class")
                    || name.startsWith("META-INF/")) {
                    continue;
                }
                fqns.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
            }
        } catch (IOException e) {
            // A jar that cannot be read (missing/corrupt) simply contributes no classes; the caller still
            // sees the artifact itself (from dependency:list), it just never matches any logger.
            return List.of();
        }
        return fqns;
    }
}
