package org.log2code.analyzer.deps;

import java.util.List;

/** The resolved runtime dependency list of one project module (T12 step 1). */
public record ModuleDeps(String module, List<Artifact> artifacts) {
}
