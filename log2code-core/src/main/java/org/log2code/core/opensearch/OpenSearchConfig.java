package org.log2code.core.opensearch;

import java.time.Duration;

/** Connection settings for {@link OpenSearchClientFactory}. */
public record OpenSearchConfig(
    String url,
    String username,
    String password,
    Duration connectTimeout,
    Duration socketTimeout
) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);

    public OpenSearchConfig {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        if (connectTimeout == null) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (socketTimeout == null) {
            socketTimeout = DEFAULT_SOCKET_TIMEOUT;
        }
    }

    /** No authentication, default timeouts. */
    public static OpenSearchConfig of(String url) {
        return new OpenSearchConfig(url, null, null, DEFAULT_CONNECT_TIMEOUT, DEFAULT_SOCKET_TIMEOUT);
    }
}
