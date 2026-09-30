package org.log2code.api.llm.explain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.log2code.api.web.LogNotFoundException;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;

/** Builds the exact prompt for a log and level (T41): system prompt, user prompt and the list of sections. */
public final class ExplainPromptService {

    /** Classpath location of the system prompt; a text change means a new file ({@code -v2}) and a new {@link ExplainPrompt#PROMPT_VERSION}. */
    static final String SYSTEM_PROMPT_RESOURCE = "/llm/explain-system-v" + ExplainPrompt.PROMPT_VERSION + ".md";

    private final DocumentReader documentReader;
    private final IndexNames indexNames;
    private final ExplainContextBuilder contextBuilder;
    private final ExplainPromptRenderer renderer;
    private final String systemPrompt;

    public ExplainPromptService(DocumentReader documentReader, IndexNames indexNames, ExplainContextBuilder contextBuilder,
            ExplainPromptRenderer renderer) {
        this.documentReader = documentReader;
        this.indexNames = indexNames;
        this.contextBuilder = contextBuilder;
        this.renderer = renderer;
        this.systemPrompt = loadSystemPrompt();
    }

    /** @throws LogNotFoundException when {@code logId} has no document in {@code log2code-logs} */
    public ExplainPrompt build(String logId, ExplainLevel level) {
        return renderer.render(contextBuilder.build(fetchLog(logId), level), systemPrompt);
    }

    private EnrichedLog fetchLog(String logId) {
        EnrichedLog log;
        try {
            log = documentReader.get(indexNames.logs(), logId, EnrichedLog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (log == null) {
            throw new LogNotFoundException(logId);
        }
        return log;
    }

    /** Read once at startup; the file's final line break is not part of the prompt. */
    private static String loadSystemPrompt() {
        try (InputStream in = ExplainPromptService.class.getResourceAsStream(SYSTEM_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + SYSTEM_PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
