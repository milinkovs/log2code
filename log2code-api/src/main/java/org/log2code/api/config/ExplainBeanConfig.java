package org.log2code.api.config;

import org.log2code.api.llm.explain.ExplainContextBuilder;
import org.log2code.api.llm.explain.ExplainPromptRenderer;
import org.log2code.api.llm.explain.ExplainPromptService;
import org.log2code.api.service.LogNeighborhoodService;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the "Explain" prompt services (T41). */
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
}
