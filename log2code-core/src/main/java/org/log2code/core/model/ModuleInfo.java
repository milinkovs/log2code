package org.log2code.core.model;

import java.util.List;

/** One Maven module analyzed within a project run. Dependencies are {@code groupId:artifactId:version}. */
public record ModuleInfo(
    String module,
    String service,
    List<String> sourceRoots,
    List<String> dependencies,
    List<String> selectedDependencies
) {
}
