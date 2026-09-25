package org.log2code.api.dto;

/** One caller row in {@link ContextBundleDto#callers()} (T25 step 1): one level, at most 10. */
public record ContextCallerDto(String classFqn, String methodName, String filePath, int line, String snippet) {
}
