package org.log2code.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.log2code.core.json.Json;

/**
 * Encodes/decodes the opaque {@code searchAfter} cursor (T23 step 3): base64 of the JSON array of
 * OpenSearch sort values for the last returned hit. Callers must treat the string as opaque.
 */
public final class SearchAfterCodec {

    private static final ObjectMapper MAPPER = Json.mapper();
    private static final TypeReference<List<String>> SORT_VALUES_TYPE = new TypeReference<>() {
    };

    private SearchAfterCodec() {
    }

    public static String encode(List<String> sortValues) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(sortValues);
            return Base64.getEncoder().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode searchAfter cursor", e);
        }
    }

    /** @throws IllegalArgumentException if {@code cursor} is not a value this codec produced. */
    public static List<String> decode(String cursor) {
        try {
            byte[] json = Base64.getDecoder().decode(cursor.getBytes(StandardCharsets.UTF_8));
            return MAPPER.readValue(json, SORT_VALUES_TYPE);
        } catch (RuntimeException | IOException e) {
            throw new IllegalArgumentException("invalid searchAfter cursor", e);
        }
    }
}
