package org.log2code.api.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.CodeUnitSummary;
import org.log2code.api.dto.DatasetSummary;
import org.log2code.api.service.MetaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link MetaController}. */
@WebMvcTest(MetaController.class)
class MetaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MetaService metaService;

    @Test
    void datasetsReturnsIdAndCount() throws Exception {
        when(metaService.datasets()).thenReturn(List.of(new DatasetSummary("smoke-01", 1045)));

        mockMvc.perform(get("/api/meta/datasets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].datasetId").value("smoke-01"))
            .andExpect(jsonPath("$[0].count").value(1045));
    }

    @Test
    void servicesPassesDatasetIdThrough() throws Exception {
        when(metaService.services("smoke-01")).thenReturn(List.of("customers-service", "visits-service"));

        mockMvc.perform(get("/api/meta/services").param("datasetId", "smoke-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("customers-service"))
            .andExpect(jsonPath("$[1]").value("visits-service"));
    }

    @Test
    void codeUnitsReturnsRunsFromCatalog() throws Exception {
        when(metaService.codeUnits()).thenReturn(List.of(
            new CodeUnitSummary("project", "spring-petclinic-microservices", "3858f9c",
                Instant.parse("2026-09-21T10:00:00Z"))));

        mockMvc.perform(get("/api/meta/code-units"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].kind").value("project"))
            .andExpect(jsonPath("$[0].name").value("spring-petclinic-microservices"))
            .andExpect(jsonPath("$[0].version").value("3858f9c"));
    }
}
