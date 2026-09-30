package org.log2code.api.llm.explain;

import java.util.List;

/**
 * One part of the prompt, as reported to the UI. {@code id} is one of {@link #ALL_IDS}; {@code reason} is
 * {@code null} for a part that is included in full, otherwise why it is missing ({@code level},
 * {@code unmatched}, {@code library}, {@code noException}, {@code noCallers}, {@code unavailable},
 * {@code truncated}, ...). The reason never appears in the prompt text itself.
 */
public record ExplainSection(String id, boolean included, String reason) {

    public static final String LOG = "log";
    public static final String EXCEPTION = "exception";
    public static final String STATEMENT = "statement";
    public static final String METHOD = "method";
    public static final String FLOW = "flow";
    public static final String STACK_CODE = "stackCode";
    public static final String CALLERS = "callers";
    public static final String NEIGHBORS = "neighbors";

    /** Stable ids, in prompt order (10.1). */
    public static final List<String> ALL_IDS =
        List.of(LOG, EXCEPTION, STATEMENT, METHOD, FLOW, STACK_CODE, CALLERS, NEIGHBORS);

    public static final String REASON_LEVEL = "level";
    public static final String REASON_UNMATCHED = "unmatched";
    public static final String REASON_LIBRARY = "library";
    public static final String REASON_NO_EXCEPTION = "noException";
    public static final String REASON_NO_CALLERS = "noCallers";
    public static final String REASON_NO_CONTROL = "noControl";
    public static final String REASON_NO_PROJECT_FRAMES = "noProjectFrames";
    public static final String REASON_NO_NEIGHBORS = "noNeighbors";
    public static final String REASON_UNAVAILABLE = "unavailable";
    public static final String REASON_TRUNCATED = "truncated";

    public static ExplainSection included(String id) {
        return new ExplainSection(id, true, null);
    }

    public static ExplainSection omitted(String id, String reason) {
        return new ExplainSection(id, false, reason);
    }
}
