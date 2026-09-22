package org.log2code.core.opensearch;

import java.io.IOException;
import java.net.URISyntaxException;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.log2code.core.json.Json;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

/** Builds a configured {@link OpenSearchClient} from an {@link OpenSearchConfig}. */
public final class OpenSearchClientFactory {

    private OpenSearchClientFactory() {
    }

    /** Creates a client. The returned {@link OpenSearchClient} wraps a closeable transport: close it via {@link #close(OpenSearchClient)}. */
    public static OpenSearchClient create(OpenSearchConfig config) {
        HttpHost host;
        try {
            host = HttpHost.create(config.url());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid OpenSearch url: " + config.url(), e);
        }

        BasicCredentialsProvider credentialsProvider = null;
        if (config.username() != null && !config.username().isBlank()) {
            credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                new AuthScope(host),
                new UsernamePasswordCredentials(config.username(), config.password() == null ? new char[0] : config.password().toCharArray())
            );
        }
        BasicCredentialsProvider finalCredentialsProvider = credentialsProvider;

        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host)
            .setMapper(new JacksonJsonpMapper(Json.mapper()))
            .setConnectionConfigCallback(cb -> cb
                .setConnectTimeout(Timeout.ofMilliseconds(config.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(config.socketTimeout().toMillis())))
            .setHttpClientConfigCallback(hc -> {
                // httpclient5's own transparent Accept-Encoding/gzip negotiation misfires against
                // OpenSearch responses ("Not in GZIP format"); opensearch-java's own compression
                // flag stays off too (ApacheHttpClient5TransportBuilder default), so this is a
                // plain, uncompressed connection end to end.
                hc.disableContentCompression();
                if (finalCredentialsProvider != null) {
                    hc.setDefaultCredentialsProvider(finalCredentialsProvider);
                }
                return hc;
            });

        ApacheHttpClient5Transport transport = builder.build();
        return new OpenSearchClient(transport);
    }

    /** Closes the transport underlying a client created by {@link #create(OpenSearchConfig)}. */
    public static void close(OpenSearchClient client) throws IOException {
        client._transport().close();
    }
}
