package org.log2code.api.config;

import org.log2code.api.llm.GeminiClient;
import org.log2code.api.llm.LlmClient;
import org.log2code.api.llm.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the LLM client and its {@code log2code.llm.*} properties (T40). */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmBeanConfig {

    @Bean
    public LlmClient llmClient(LlmProperties properties, JsonMapper jsonMapper) {
        return new GeminiClient(properties, jsonMapper);
    }
}
