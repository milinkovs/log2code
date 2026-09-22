package org.log2code.core.model;

/** One caller of a project method (level 3, T13). */
public record CallerRef(String methodId, String classFqn, String methodName, String fileId, int line) {
}
