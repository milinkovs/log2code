package org.log2code.analyzer.deps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.log2code.core.logger.LoggerNameMatcher;

/**
 * Automatic dependency selection (T12 step 2): for one module, decides which of its runtime artifacts are
 * "selected" (their sources will be downloaded and later analyzed by T14), based on whether the module's
 * observed loggers ({@link LoggerHeaderScanner}) resolve to a class inside that artifact's jar
 * ({@link JarClassIndex}, matched via {@link LoggerNameMatcher}), plus {@code dependencies.include}/
 * {@code exclude} from {@code config/analyzer.yml} (0.11).
 */
public final class DependencySelector {

    private DependencySelector() {
    }

    public record SelectedArtifact(Artifact artifact, boolean selected, String reason, int loggerHits) {
    }

    /**
     * {@code excludedLoggers}: loggers that are not shaped like a class name at all (e.g. Tomcat's
     * {@code Catalina.[Tomcat].[localhost]} container hierarchy) — these can never match under 0.10 step 1,
     * so they are kept out of the AC4 "unique loggers" denominator entirely, not counted as failed matches.
     */
    public record ModuleSelection(String module, List<SelectedArtifact> artifacts, Set<String> mappedLoggers,
                                   Set<String> unmappedLoggers, Set<String> excludedLoggers) {
    }

    public static ModuleSelection select(ModuleDeps deps, Map<String, Integer> loggerCounts, Set<String> projectFqns,
                                          List<String> includePatterns, List<String> excludePatterns) {
        Set<String> mappedToProject = new TreeSet<>();
        Set<String> libraryLoggers = new TreeSet<>();
        Set<String> excludedLoggers = new TreeSet<>();
        for (String loggerRaw : loggerCounts.keySet()) {
            if (!LoggerNameMatcher.looksLikeClassName(loggerRaw)) {
                excludedLoggers.add(loggerRaw);
            } else if (matchesAnyFqn(loggerRaw, projectFqns)) {
                mappedToProject.add(loggerRaw);
            } else {
                libraryLoggers.add(loggerRaw);
            }
        }

        Map<Artifact, Integer> hitsByArtifact = new LinkedHashMap<>();
        Set<String> mappedToLibrary = new TreeSet<>();
        for (Artifact artifact : deps.artifacts()) {
            List<String> classFqns = JarClassIndex.classFqns(artifact.jarPath());
            int hits = 0;
            for (String loggerRaw : libraryLoggers) {
                if (matchesAnyFqn(loggerRaw, classFqns)) {
                    hits++;
                    mappedToLibrary.add(loggerRaw);
                }
            }
            hitsByArtifact.put(artifact, hits);
        }

        List<Pattern> includeRegex = includePatterns.stream().map(DependencySelector::globToPattern).toList();
        List<Pattern> excludeRegex = excludePatterns.stream().map(DependencySelector::globToPattern).toList();

        List<SelectedArtifact> result = new ArrayList<>();
        for (Artifact artifact : deps.artifacts()) {
            int hits = hitsByArtifact.getOrDefault(artifact, 0);
            boolean excluded = matchesAnyPattern(artifact.groupArtifact(), excludeRegex);
            boolean seenInLogs = hits > 0;
            boolean included = matchesAnyPattern(artifact.groupArtifact(), includeRegex);

            boolean selected;
            String reason;
            if (excluded) {
                selected = false;
                reason = "config-exclude";
            } else if (seenInLogs) {
                selected = true;
                reason = "seen-in-logs";
            } else if (included) {
                selected = true;
                reason = "config-include";
            } else {
                selected = false;
                reason = null;
            }
            result.add(new SelectedArtifact(artifact, selected, reason, hits));
        }

        Set<String> mapped = new TreeSet<>(mappedToProject);
        mapped.addAll(mappedToLibrary);
        Set<String> unmapped = new TreeSet<>(libraryLoggers);
        unmapped.removeAll(mappedToLibrary);

        return new ModuleSelection(deps.module(), result, mapped, unmapped, excludedLoggers);
    }

    private static boolean matchesAnyFqn(String loggerRaw, Set<String> fqns) {
        for (String fqn : fqns) {
            if (LoggerNameMatcher.matches(loggerRaw, fqn)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyFqn(String loggerRaw, List<String> fqns) {
        for (String fqn : fqns) {
            if (LoggerNameMatcher.matches(loggerRaw, fqn)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyPattern(String groupArtifact, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(groupArtifact).matches()) {
                return true;
            }
        }
        return false;
    }

    /** {@code *} wildcard glob (0.11: "obrasci groupId:artifactId, npr. org.springframework:*"). */
    private static Pattern globToPattern(String glob) {
        String[] parts = glob.split("\\*", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }
}
