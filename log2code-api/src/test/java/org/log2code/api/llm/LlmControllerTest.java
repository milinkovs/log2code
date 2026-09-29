package org.log2code.api.llm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code @WebMvcTest} for {@link LlmController}. */
@WebMvcTest(LlmController.class)
@Import(LlmControllerTest.Props.class)
class LlmControllerTest {

    private static final String SECRET = "secret-key-value";

    static LlmProperties props(String apiKey) {
        return new LlmProperties("gemini", "http://unused", apiKey, "flash",
            List.of(new LlmProperties.Model("flash", "Flash"), new LlmProperties.Model("lite", "Lite")),
            0.2, 8192, Duration.ofSeconds(10), Duration.ofSeconds(120));
    }

    @TestConfiguration
    static class Props {
        @Bean
        LlmProperties llmProperties() {
            return props(SECRET);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsModelsAndNeverReturnsTheKey() throws Exception {
        mockMvc.perform(get("/api/llm/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.defaultModel").value("flash"))
            .andExpect(jsonPath("$.models[0].id").value("flash"))
            .andExpect(jsonPath("$.models[0].label").value("Flash"))
            .andExpect(jsonPath("$.models[1].id").value("lite"))
            .andExpect(jsonPath("$.apiKey").doesNotExist())
            .andExpect(content().string(Matchers.not(Matchers.containsString(SECRET))));
    }

    @Test
    void emptyKeyMeansNotConfigured() throws Exception {
        MockMvc standalone = MockMvcBuilders.standaloneSetup(new LlmController(props(""))).build();

        standalone.perform(get("/api/llm/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false))
            .andExpect(jsonPath("$.defaultModel").value("flash"))
            .andExpect(jsonPath("$.apiKey").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(props("  ").configured()).isFalse();
        org.assertj.core.api.Assertions.assertThat(props(null).configured()).isFalse();
    }

    @Test
    void propertiesRejectDefaultModelOutsideList() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LlmProperties("gemini", "u", "k", "missing",
                List.of(new LlmProperties.Model("flash", "Flash")), 0.2, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LlmProperties("gemini", "u", "k", "flash",
                List.of(), 0.2, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
