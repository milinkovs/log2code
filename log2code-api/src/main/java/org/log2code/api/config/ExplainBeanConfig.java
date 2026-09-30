package org.log2code.api.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.log2code.api.llm.LlmClient;
import org.log2code.api.llm.LlmProperties;
import org.log2code.api.llm.explain.ExplainContextBuilder;
import org.log2code.api.llm.explain.ExplainPromptRenderer;
import org.log2code.api.llm.explain.ExplainPromptService;
import org.log2code.api.llm.explain.ExplainStreamService;
import org.log2code.api.service.LogNeighborhoodService;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the "Explain" prompt services (T41) and the SSE stream (T42). */
@Configuration
public class ExplainBeanConfig {

    @Bean
    public ExplainContextBuilder explainContextBuilder(DocumentReader documentReader, IndexNames indexNames,
            LogNeighborhoodService logNeighborhoodService) {
        return new ExplainContextBuilder(documentReader, indexNames, logNeighborhoodService);
    }

    @Bean
    public ExplainPromptRenderer explainPromptRenderer() {
        return new ExplainPromptRenderer();
    }

    @Bean
    public ExplainPromptService explainPromptService(DocumentReader documentReader, IndexNames indexNames,
            ExplainContextBuilder contextBuilder, ExplainPromptRenderer renderer) {
        return new ExplainPromptService(documentReader, indexNames, contextBuilder, renderer);
    }

    /** One virtual thread per stream: a stream mostly waits on the network, so it must not hold a platform thread. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService explainExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ExplainStreamService explainStreamService(ExplainPromptService promptService, LlmClient llmClient,
            LlmProperties properties, JsonMapper jsonMapper, ExecutorService explainExecutor) {
        return new ExplainStreamService(promptService, llmClient, properties, jsonMapper, explainExecutor);
    }
}
