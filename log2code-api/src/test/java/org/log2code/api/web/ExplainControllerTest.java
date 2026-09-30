package org.log2code.api.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.llm.explain.ExplainLevel;
import org.log2code.api.llm.explain.ExplainPrompt;
import org.log2code.api.llm.explain.ExplainPromptService;
import org.log2code.api.llm.explain.ExplainSection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link ExplainController}: the prompt service is mocked (T41 step 7). */
@WebMvcTest(ExplainController.class)
class ExplainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExplainPromptService promptService;

    private static ExplainPrompt prompt(ExplainLevel level) {
        return new ExplainPrompt(1, level, "system text", "user text", 9, List.of(
            ExplainSection.included("log"), ExplainSection.omitted("statement", "unmatched"),
            new ExplainSection("callers", true, "truncated")));
    }

    @Test
    void returnsThePromptAndItsSections() throws Exception {
        when(promptService.build("log-1", ExplainLevel.L3)).thenReturn(prompt(ExplainLevel.L3));

        mockMvc.perform(get("/api/logs/log-1/explain/prompt").param("level", "L3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.promptVersion").value(1))
            .andExpect(jsonPath("$.level").value("L3"))
            .andExpect(jsonPath("$.systemPrompt").value("system text"))
            .andExpect(jsonPath("$.userPrompt").value("user text"))
            .andExpect(jsonPath("$.promptChars").value(9))
            .andExpect(jsonPath("$.sections.length()").value(3))
            .andExpect(jsonPath("$.sections[0].id").value("log"))
            .andExpect(jsonPath("$.sections[0].included").value(true))
            .andExpect(jsonPath("$.sections[1].included").value(false))
            .andExpect(jsonPath("$.sections[1].reason").value("unmatched"))
            .andExpect(jsonPath("$.sections[2].reason").value("truncated"));
    }

    @Test
    void theDefaultLevelIsL2() throws Exception {
        when(promptService.build("log-1", ExplainLevel.L2)).thenReturn(prompt(ExplainLevel.L2));

        mockMvc.perform(get("/api/logs/log-1/explain/prompt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.level").value("L2"));
    }

    @Test
    void anInvalidLevelIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/logs/log-1/explain/prompt").param("level", "L9"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("level must be one of L0, L1, L2, L3, L4"));
    }

    @Test
    void anUnknownLogIsNotFound() throws Exception {
        when(promptService.build("missing", ExplainLevel.L2)).thenThrow(new LogNotFoundException("missing"));

        mockMvc.perform(get("/api/logs/missing/explain/prompt"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("log not found: missing"));
    }
}
