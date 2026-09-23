package org.log2code.analyzer.catalog;

/**
 * Where one parsed {@code .java} file lives in the project (0.7): {@code file_path} is relative to the
 * repository root, separator {@code /} - {@code <module>/<source-root>/<path within source root>}.
 */
public record FileInfo(String module, String service, String filePath, String packageName) {
}
