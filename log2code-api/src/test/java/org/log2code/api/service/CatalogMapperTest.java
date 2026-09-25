package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.CatalogEntryDto;
import org.log2code.core.model.CallSite;
import org.log2code.core.model.CatalogEntry;
import org.log2code.core.model.CodeUnit;
import org.log2code.core.model.Condition;
import org.log2code.core.model.ControlContext;
import org.log2code.core.model.EarlyExit;
import org.log2code.core.model.EnclosingBlock;
import org.log2code.core.model.Level;
import org.log2code.core.model.PrecedingStatement;

/** T24 step 1: {@link CatalogMapper} must drop {@code regex} and carry the full {@code control} context. */
class CatalogMapperTest {

    @Test
    void toDtoMapsControlContextAndOmitsRegex() {
        ControlContext control = new ControlContext(
            List.of(new Condition("if", "ownerId > 0", 85, false)),
            List.of(new EarlyExit("owner == null", 84, "return")),
            List.of(new PrecedingStatement("call", "ownerRepository.findById(ownerId)", 86)),
            List.of(new CallSite(86, "ownerRepository.findById(ownerId)", "ownerRepository.findById", "method-2", true))
        );
        CatalogEntry entry = entry(control, new EnclosingBlock("method", null, null, 83, 91), Level.INFO);

        CatalogEntryDto dto = CatalogMapper.toDto(entry);

        assertThat(dto.statementId()).isEqualTo("stmt-1");
        assertThat(dto.codeUnit().name()).isEqualTo("petclinic");
        assertThat(dto.level()).isEqualTo("INFO");
        assertThat(dto.control().conditions()).hasSize(1);
        assertThat(dto.control().conditions().get(0).kind()).isEqualTo("if");
        assertThat(dto.control().earlyExits()).hasSize(1);
        assertThat(dto.control().preceding()).hasSize(1);
        assertThat(dto.control().callsBefore()).hasSize(1);
        assertThat(dto.control().callsBefore().get(0).targetMethodId()).isEqualTo("method-2");
        assertThat(dto.enclosing().blockKind()).isEqualTo("method");
    }

    @Test
    void toDtoToleratesNullControlEnclosingAndLevel() {
        CatalogEntry entry = entry(null, null, null);

        CatalogEntryDto dto = CatalogMapper.toDto(entry);

        assertThat(dto.control()).isNull();
        assertThat(dto.enclosing()).isNull();
        assertThat(dto.level()).isNull();
    }

    private static CatalogEntry entry(ControlContext control, EnclosingBlock enclosing, Level level) {
        return new CatalogEntry(
            "stmt-1", "logical-1", new CodeUnit(CodeUnit.TYPE_PROJECT, "petclinic", "sha1"),
            "spring-petclinic-customers-service", "customers-service", "OwnerResource.java", "file-1",
            "customers.web", "OwnerResource", "OwnerResource", "updateOwner", "updateOwner(int,OwnerRequest)",
            "method-1", false, 89, 89, 8, 83, 91, "slf4j", "typed", "log", "OwnerResource", "class_literal",
            level, false, "\"Saving owner {}\"", "Saving owner {}", "placeholders", null,
            "^Saving owner (.*)$", List.of("Saving", "owner"), 12, 1, false, enclosing, control,
            "log.info(...)", 86, "https://example.invalid/OwnerResource.java#L89", "0.1.0-SNAPSHOT",
            Instant.parse("2026-09-23T10:00:00Z"));
    }
}
