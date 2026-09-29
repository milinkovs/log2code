package org.log2code.api.llm;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code log2code.llm.*} (T40 step 3). An empty {@code apiKey} is not an error: it means "not configured"
 * ({@link #configured()}), so the API still starts without a key.
 */
@ConfigurationProperties("log2code.llm")
public record LlmProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String defaultModel,
        List<Model> models,
        double temperature,
        int maxOutputTokens,
        Duration connectTimeout,
        Duration requestTimeout) {

    /** One selectable model: {@code id} without the {@code models/} prefix, {@code label} for the UI. */
    public record Model(String id, String label) {
    }

    public LlmProperties {
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("log2code.llm.models must not be empty");
        }
        if (defaultModel == null || models.stream().noneMatch(m -> m.id().equals(defaultModel))) {
            throw new IllegalArgumentException(
                "log2code.llm.default-model '" + defaultModel + "' is not listed in log2code.llm.models");
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        models = List.copyOf(models);
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }
}
