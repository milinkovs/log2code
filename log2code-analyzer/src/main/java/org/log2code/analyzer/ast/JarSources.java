package org.log2code.analyzer.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds and parses every {@code .java} entry directly inside a {@code -sources.jar} (T14 step 2), via
 * {@link ZipFile} - the jar is never unpacked to disk. Parsing is syntax-only, exactly like
 * {@link JavaSources} (same {@link ParserConfiguration}), which is what lets T08's detection run
 * unmodified against a dependency's sources (ADR-008). {@code relativePath} uses {@code /} (a zip
 * entry's own name), matching 0.7's "putanja u sources jar-u" for a dependency's {@code file_path}.
 */
public final class JarSources {

    private static final ParserConfiguration CONFIG =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private JarSources() {
    }

    /** One parsed {@code .java} jar entry: its path within the jar, AST and raw UTF-8 bytes. */
    public record ParsedFile(String relativePath, CompilationUnit unit, byte[] bytes) {
    }

    public record ParseFailure(String relativePath, String message) {
    }

    public record Result(List<ParsedFile> files, List<ParseFailure> failures) {
    }

    /** Parses every {@code .java} entry in {@code jarFile}, in a deterministic (sorted) order. */
    public static Result parseAll(Path jarFile) {
        JavaParser parser = new JavaParser(CONFIG);
        List<ParsedFile> parsed = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            for (String entryName : sortedJavaEntryNames(zip)) {
                ZipEntry entry = zip.getEntry(entryName);
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                } catch (IOException e) {
                    failures.add(new ParseFailure(entryName, e.getMessage()));
                    continue;
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                ParseResult<CompilationUnit> result = parser.parse(content);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    parsed.add(new ParsedFile(entryName, result.getResult().get(), bytes));
                } else {
                    failures.add(new ParseFailure(entryName, result.getProblems().toString()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + jarFile, e);
        }
        return new Result(List.copyOf(parsed), List.copyOf(failures));
    }

    private static List<String> sortedJavaEntryNames(ZipFile zip) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith(".java")) {
                names.add(entry.getName());
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }
}
