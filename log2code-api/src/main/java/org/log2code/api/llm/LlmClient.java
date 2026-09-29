package org.log2code.api.llm;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Provider-neutral streaming text generation (T40 step 4). */
public interface LlmClient {

    /**
     * Sends {@code request} and passes each piece of generated text to {@code onDelta} as it arrives.
     * When {@code cancelled} turns {@code true} the stream is closed and the result has
     * {@code finishReason = "CANCELLED"}.
     */
    LlmResult stream(LlmRequest request, Consumer<String> onDelta, BooleanSupplier cancelled) throws LlmException;
}
