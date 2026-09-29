package org.log2code.api.llm;

/** Failure of an LLM call. The message never contains the API key. */
public class LlmException extends RuntimeException {

    public enum Kind {
        NOT_CONFIGURED,
        INVALID_KEY,
        RATE_LIMITED,
        BLOCKED,
        TIMEOUT,
        UPSTREAM
    }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
