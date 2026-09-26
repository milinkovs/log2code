package org.log2code.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.DatasetSummary;
import org.log2code.api.service.MetaService;
import org.log2code.api.web.MetaController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Static web build and SPA fallback (T32 step 2), against {@code src/test/resources/spa-test/}. */
@WebMvcTest(controllers = MetaController.class,
    properties = "spring.web.resources.static-locations=classpath:/spa-test/")
class SpaFallbackTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MetaService metaService;

    @Test
    void clientRoutesReturnIndexHtml() throws Exception {
        for (String path : List.of("/logs/dcf3be3719357b10ac07fe9b2aed1bd4", "/logs/abc?datasetId=smoke-01",
            "/some/unknown/route")) {
            mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")));
        }
    }

    @Test
    void rootIsBootsWelcomePageForwardingToIndexHtml() throws Exception {
        // served by the fallback handler after the forward; MockMvc does not follow forwards
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
    }

    @Test
    void existingStaticFilesAreServedAsThemselves() throws Exception {
        mockMvc.perform(get("/assets/app.js"))
            .andExpect(status().isOk())
            .andExpect(content().string("console.log(\"app\");\n"));
        mockMvc.perform(get("/favicon.svg"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
    }

    @Test
    void missingFilesWithAnExtensionAreNotFound() throws Exception {
        mockMvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    }

    @Test
    void apiAndActuatorPathsNeverFallBack() throws Exception {
        mockMvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void apiRoutesStillReachControllers() throws Exception {
        when(metaService.datasets()).thenReturn(List.of(new DatasetSummary("smoke-01", 1045)));

        mockMvc.perform(get("/api/meta/datasets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].datasetId").value("smoke-01"));
    }

    @Test
    void clientRouteClassification() {
        assertThat(SpaResourceResolver.isClientRoute("")).isTrue();
        assertThat(SpaResourceResolver.isClientRoute("logs/abc")).isTrue();
        assertThat(SpaResourceResolver.isClientRoute("apis/x")).isTrue();
        assertThat(SpaResourceResolver.isClientRoute("api")).isFalse();
        assertThat(SpaResourceResolver.isClientRoute("api/logs")).isFalse();
        assertThat(SpaResourceResolver.isClientRoute("/actuator/health")).isFalse();
        assertThat(SpaResourceResolver.isClientRoute("assets/index-abc.js")).isFalse();
    }
}
