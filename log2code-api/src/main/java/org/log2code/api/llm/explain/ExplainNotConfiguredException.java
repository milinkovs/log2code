package org.log2code.api.llm.explain;

/** No Gemini API key is set: {@code POST .../explain} answers 503 with {@code code: llm_not_configured} before any stream. */
public class ExplainNotConfiguredException extends RuntimeException {

    public static final String CODE = "llm_not_configured";

    public ExplainNotConfiguredException() {
        super("Explain needs a Gemini API key: set LOG2CODE_GEMINI_API_KEY in infra/.env.local and restart log2code-api");
    }
}
