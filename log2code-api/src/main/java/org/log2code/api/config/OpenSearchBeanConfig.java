package org.log2code.api.config;

import org.log2code.api.service.LogSearchService;
import org.log2code.api.service.MetaService;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.log2code.core.opensearch.OpenSearchClientFactory;
import org.log2code.core.opensearch.OpenSearchConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@link OpenSearchClient} used by every controller/service, per T23 step 1. */
@Configuration
public class OpenSearchBeanConfig {

    @Bean
    public OpenSearchConfig openSearchConfig(@Value("${log2code.opensearch.url}") String url) {
        return OpenSearchConfig.of(url);
    }

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchConfig config) {
        return OpenSearchClientFactory.create(config);
    }

    /** Closes the transport underlying {@link #openSearchClient} on context shutdown. */
    @Bean
    public DisposableBean openSearchClientCloser(OpenSearchClient client) {
        return () -> OpenSearchClientFactory.close(client);
    }

    @Bean
    public IndexNames indexNames() {
        return new IndexNames();
    }

    @Bean
    public DocumentReader documentReader(OpenSearchClient client) {
        return new DocumentReader(client);
    }

    @Bean
    public LogSearchService logSearchService(OpenSearchClient client, IndexNames indexNames) {
        return new LogSearchService(client, indexNames);
    }

    @Bean
    public MetaService metaService(OpenSearchClient client, IndexNames indexNames) {
        return new MetaService(client, indexNames);
    }
}
