package org.log2code.analyzer.graph;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.log2code.core.model.ModuleInfo;

/**
 * Builds the {@link CombinedTypeSolver} for one module (T13 step 1): a JRE-only {@link
 * ReflectionTypeSolver}, one {@link JavaParserTypeSolver} per module's own {@code src/main/java}
 * ("svaki src/main/java projekta"), and that module's own dependency jars ("samo za modul koji se
 * trenutno obrađuje"). Every {@link com.github.javaparser.resolution.TypeSolver} is built fresh for
 * each module and never reused across two {@link CombinedTypeSolver}s: {@code CombinedTypeSolver.add}
 * calls {@code setParent} on the child, which throws {@code IllegalStateException} if that child
 * already belongs to an earlier module's combined solver - a plain jar/source-root cache across
 * modules is therefore not an option here (found by an {@code -Pit} run failing on the second module,
 * not by inspection - ADR-013).
 */
final class ModuleTypeSolvers {

    private final Path projectRoot;
    private final List<ModuleInfo> modules;
    private final ParserConfiguration parserConfiguration =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    private int jarLoadFailures;

    ModuleTypeSolvers(Path projectRoot, List<ModuleInfo> modules) {
        this.projectRoot = projectRoot;
        this.modules = modules;
    }

    CombinedTypeSolver forModule(List<Path> jarPaths) {
        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(new ReflectionTypeSolver(true));
        for (ModuleInfo module : modules) {
            for (String sourceRoot : module.sourceRoots()) {
                combined.add(new JavaParserTypeSolver(projectRoot.resolve(module.module()).resolve(sourceRoot), parserConfiguration));
            }
        }
        for (Path jar : jarPaths) {
            try {
                combined.add(new JarTypeSolver(jar));
            } catch (IOException e) {
                jarLoadFailures++;
            }
        }
        return combined;
    }

    /** How many jars from {@code deps-manifest.json} could not be opened (corrupt/moved since it was written). */
    int jarLoadFailures() {
        return jarLoadFailures;
    }
}
