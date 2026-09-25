package org.log2code.api.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.log2code.core.ids.StableIds;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.SourceFile;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link SourcesController} (T24 step 1). */
@WebMvcTest(SourcesController.class)
class SourcesControllerTest {

    private static final String SOURCES_INDEX = "log2code-sources";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentReader documentReader;
    @MockitoBean
    private IndexNames indexNames;

    @Test
    void getReturnsFileWithEtagAndCacheControl() throws Exception {
        when(indexNames.sources()).thenReturn(SOURCES_INDEX);
        when(documentReader.get(eq(SOURCES_INDEX), eq("file-1"), eq(SourceFile.class))).thenReturn(sampleSource());

        mockMvc.perform(get("/api/sources/file-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileId").value("file-1"))
            .andExpect(jsonPath("$.content").value("class OwnerResource {}"))
            .andExpect(jsonPath("$.lineCount").value(1))
            .andExpect(header().string("ETag", "\"abc123\""))
            .andExpect(header().string("Cache-Control", "max-age=31536000, immutable"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(indexNames.sources()).thenReturn(SOURCES_INDEX);
        when(documentReader.get(eq(SOURCES_INDEX), eq("missing"), eq(SourceFile.class))).thenReturn(null);

        mockMvc.perform(get("/api/sources/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("source file not found: missing"));
    }

    @Test
    void lookupReturnsComputedFileIdWhenItExists() throws Exception {
        when(indexNames.sources()).thenReturn(SOURCES_INDEX);
        String expectedFileId = StableIds.fileId("petclinic", "sha1", "OwnerResource.java");
        when(documentReader.exists(SOURCES_INDEX, expectedFileId)).thenReturn(true);

        mockMvc.perform(get("/api/sources/lookup")
                .param("codeUnit", "petclinic").param("version", "sha1").param("path", "OwnerResource.java"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileId").value(expectedFileId));
    }

    @Test
    void lookupReturns404WhenNoMatch() throws Exception {
        when(indexNames.sources()).thenReturn(SOURCES_INDEX);
        String missingFileId = StableIds.fileId("petclinic", "sha1", "Missing.java");
        when(documentReader.exists(SOURCES_INDEX, missingFileId)).thenReturn(false);

        mockMvc.perform(get("/api/sources/lookup")
                .param("codeUnit", "petclinic").param("version", "sha1").param("path", "Missing.java"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"));
    }

    private static SourceFile sampleSource() {
        return new SourceFile("file-1", new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic", "sha1"),
            "spring-petclinic-customers-service", "OwnerResource.java", "class OwnerResource {}", 1, "abc123");
    }
}
