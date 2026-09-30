package org.log2code.api.llm.explain;

/** Cumulative context levels for the "Explain" prompt (10.1): every level contains everything from the previous one. */
public enum ExplainLevel {
    L0, L1, L2, L3, L4;

    /** The default level of the prompt endpoint and of the UI. */
    public static final ExplainLevel DEFAULT = L2;

    /** Exactly {@code L0}..{@code L4}; anything else (including {@code null} and lower case) is rejected. */
    public static ExplainLevel parse(String value) {
        for (ExplainLevel level : values()) {
            if (level.name().equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("level must be one of L0, L1, L2, L3, L4");
    }

    public boolean atLeast(ExplainLevel other) {
        return compareTo(other) >= 0;
    }
}
