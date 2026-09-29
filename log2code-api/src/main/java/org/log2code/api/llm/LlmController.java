package org.log2code.api.llm;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/llm/models}: what the UI can offer (T40 step 4). The API key is never returned. */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    public record ModelDto(String id, String label) {
    }

    public record ModelsResponse(boolean configured, String defaultModel, List<ModelDto> models) {
    }

    private final LlmProperties properties;

    public LlmController(LlmProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/models")
    public ModelsResponse models() {
        List<ModelDto> models = properties.models().stream()
            .map(m -> new ModelDto(m.id(), m.label()))
            .toList();
        return new ModelsResponse(properties.configured(), properties.defaultModel(), models);
    }
}
