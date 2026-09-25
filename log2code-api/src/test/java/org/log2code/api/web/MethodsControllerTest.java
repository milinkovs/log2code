package org.log2code.api.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.service.MethodGraphService;
import org.log2code.core.model.CallEdge;
import org.log2code.core.model.CallerRef;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.MethodInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link MethodsController} (T24 step 1, level 3 / T13 graph). */
@WebMvcTest(MethodsController.class)
class MethodsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MethodGraphService service;

    @Test
    void getReturnsMethodDetail() throws Exception {
        MethodInfo target = targetMethod();
        when(service.fetchMethod("method-1")).thenReturn(target);
        when(service.toDetail(target)).thenCallRealMethod();

        mockMvc.perform(get("/api/methods/method-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.methodId").value("method-1"))
            .andExpect(jsonPath("$.methodName").value("map"))
            .andExpect(jsonPath("$.callerCount").value(2))
            .andExpect(jsonPath("$.calledBy[0].methodId").value("caller-1"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(service.fetchMethod("missing")).thenReturn(null);

        mockMvc.perform(get("/api/methods/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("method not found: missing"));
    }

    @Test
    void callersDelegatesToService() throws Exception {
        MethodInfo target = targetMethod();
        when(service.fetchMethod("method-1")).thenReturn(target);
        when(service.callers(target)).thenReturn(List.of(new org.log2code.api.dto.CallerDto(
            "caller-1", "OwnerResource", "updateOwner", "file-1", 88, 1, List.of("PutMapping"))));

        mockMvc.perform(get("/api/methods/method-1/callers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].methodId").value("caller-1"))
            .andExpect(jsonPath("$[0].callerCount").value(1))
            .andExpect(jsonPath("$[0].annotations[0]").value("PutMapping"));
    }

    @Test
    void callersReturns404WhenTargetMissing() throws Exception {
        when(service.fetchMethod("missing")).thenReturn(null);

        mockMvc.perform(get("/api/methods/missing/callers"))
            .andExpect(status().isNotFound());
    }

    private static MethodInfo targetMethod() {
        return new MethodInfo("method-1", new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic", "sha1"),
            "spring-petclinic-customers-service", "customers-service", "file-1",
            "OwnerEntityMapper.java", "OwnerEntityMapper", "OwnerEntityMapper", "map",
            "map(Owner,OwnerRequest)", 30, 40, List.of(), false,
            List.of(new CallEdge(35, "request.address()", null, "OwnerRequest", false, false)),
            List.of(new CallerRef("caller-1", "OwnerResource", "updateOwner", "file-1", 88),
                new CallerRef("caller-2", "OwnerResource", "createOwner", "file-1", 60)),
            2);
    }
}
