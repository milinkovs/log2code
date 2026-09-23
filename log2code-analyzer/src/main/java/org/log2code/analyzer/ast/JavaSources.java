package org.log2code.analyzer.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds and parses {@code .java} files under a source root. Parsing is syntax-only (0.6:
 * {@link ParserConfiguration.LanguageLevel#JAVA_21}, no symbol solver) - files do not need to compile,
 * which is what lets the same parsing (and T08's detection logic built on top of it) run identically
 * for the project and for a dependency's sources jar (T14), where a full classpath is not available.
 */
public final class JavaSources {

    private static final ParserConfiguration CONFIG =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private JavaSources() {
    }

    /** One parsed {@code .java} file: its path (relative to the source root it was found under) and AST. */
    public record ParsedFile(Path relativePath, CompilationUnit unit) {
    }

    public record ParseFailure(Path relativePath, String message) {
    }

    public record Result(List<ParsedFile> files, List<ParseFailure> failures) {
    }

    /** Parses every {@code .java} file under {@code sourceRoot}, in a deterministic (sorted) order. */
    public static Result parseAll(Path sourceRoot) {
        JavaParser parser = new JavaParser(CONFIG);
        List<ParsedFile> parsed = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        for (Path file : findJavaFiles(sourceRoot)) {
            Path relative = sourceRoot.relativize(file);
            try {
                String content = Files.readString(file);
                ParseResult<CompilationUnit> result = parser.parse(content);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    parsed.add(new ParsedFile(relative, result.getResult().get()));
                } else {
                    failures.add(new ParseFailure(relative, result.getProblems().toString()));
                }
            } catch (IOException e) {
                failures.add(new ParseFailure(relative, e.getMessage()));
            }
        }
        return new Result(List.copyOf(parsed), List.copyOf(failures));
    }

    /** Every {@code .java} file under {@code sourceRoot}, in a deterministic (sorted) order. */
    public static List<Path> findJavaFiles(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted(Comparator.naturalOrder())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list .java files under " + sourceRoot, e);
        }
    }
}
