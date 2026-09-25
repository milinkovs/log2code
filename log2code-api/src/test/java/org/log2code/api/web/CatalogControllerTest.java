package org.log2code.api.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.Level;
import org.log2code.core.opensearch.DocumentReader;
import org.log2code.core.opensearch.IndexNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code @WebMvcTest} for {@link CatalogController} (T24 step 1). */
@WebMvcTest(CatalogController.class)
class CatalogControllerTest {

    private static final String CATALOG_INDEX = "log2code-catalog";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentReader documentReader;
    @MockitoBean
    private IndexNames indexNames;

    @Test
    void getReturnsEntryWithControlAndWithoutRegex() throws Exception {
        when(indexNames.catalog()).thenReturn(CATALOG_INDEX);
        when(documentReader.get(eq(CATALOG_INDEX), eq("stmt-1"), eq(CatalogEntry.class))).thenReturn(sampleEntry());

        mockMvc.perform(get("/api/catalog/stmt-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.statementId").value("stmt-1"))
            .andExpect(jsonPath("$.template").value("Saving owner {}"))
            .andExpect(jsonPath("$.level").value("INFO"))
            .andExpect(jsonPath("$.control.conditions[0].kind").value("if"))
            .andExpect(jsonPath("$.regex").doesNotExist());
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(indexNames.catalog()).thenReturn(CATALOG_INDEX);
        when(documentReader.get(eq(CATALOG_INDEX), eq("missing"), eq(CatalogEntry.class))).thenReturn(null);

        mockMvc.perform(get("/api/catalog/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("catalog entry not found: missing"));
    }

    private static CatalogEntry sampleEntry() {
        ControlContext control = new ControlContext(
            List.of(new Condition("if", "ownerId > 0", 85, false)), List.of(), List.of(), List.of());
        return new CatalogEntry(
            "stmt-1",                                              // statementId
            "logical-1",                                           // logicalId
            new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic", "sha1"), // codeUnit
            "spring-petclinic-customers-service",                  // module
            "customers-service",                                   // service
            "src/main/java/OwnerResource.java",                    // filePath
            "file-1",                                              // fileId
            "customers.web",                                       // packageName
            "OwnerResource",                                       // classFqn
            "OwnerResource",                                       // classBinary
            "updateOwner",                                         // methodName
            "updateOwner(int,OwnerRequest)",                       // methodSignature
            "method-1",                                            // methodId
            false,                                                 // inLambda
            89,                                                    // line
            89,                                                    // endLine
            8,                                                     // column
            83,                                                    // methodStartLine
            91,                                                    // methodEndLine
            "slf4j",                                               // loggingApi
            "typed",                                               // detection
            "log",                                                 // loggerExpr
            "OwnerResource",                                       // loggerName
            "class_literal",                                       // loggerNameKind
            Level.INFO,                                            // level
            false,                                                 // levelDynamic
            "\"Saving owner {}\"",                                 // templateRaw
            "Saving owner {}",                                     // template
            "placeholders",                                        // templateKind
            null,                                                  // unsupportedReason
            "^Saving owner (.*)$",                                 // regex (T24: must not reach the DTO)
            List.of("Saving", "owner"),                            // constantTokens
            12,                                                    // literalLength
            1,                                                     // placeholderCount
            false,                                                 // hasThrowableArg
            new EnclosingBlock("method", null, null, 83, 91),      // enclosing
            control,                                               // control
            "log.info(...)",                                       // snippet
            86,                                                    // snippetStartLine
            "https://github.com/example/petclinic/blob/sha1/OwnerResource.java#L89", // githubUrl
            "0.1.0-SNAPSHOT",                                      // analyzerVersion
            Instant.parse("2026-09-23T10:00:00Z"));                // analyzedAt
    }
}
