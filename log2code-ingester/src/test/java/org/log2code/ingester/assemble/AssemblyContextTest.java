package org.log2code.ingester.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.log2code.core.model.CodeVersion;

/**
 * 0.7 documents every field {@link AssemblyContext} carries as an always-populated keyword on
 * {@code log2code-logs} — the compact constructor must fail fast rather than let a caller (T21)
 * silently stamp null/blank metadata onto an entire file's worth of events.
 */
class AssemblyContextTest {

    private static final CodeVersion CODE = new CodeVersion("spring-petclinic-microservices", "abc123");

    @Test
    void validContextIsAccepted() {
        AssemblyContext ctx = new AssemblyContext("d", "f.log", "customers-service", "spring-petclinic-customers-service",
            CODE, "spring-boot-default", false);
        assertThat(ctx.service()).isEqualTo("customers-service");
    }

    @Test
    void rejectsNullDatasetId() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new AssemblyContext(null, "f.log", "s", "m", CODE, "spring-boot-default", false));
    }

    @Test
    void rejectsBlankSourceFile() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new AssemblyContext("d", "   ", "s", "m", CODE, "spring-boot-default", false));
    }

    @Test
    void rejectsNullService() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new AssemblyContext("d", "f.log", null, "m", CODE, "spring-boot-default", false));
    }

    @Test
    void rejectsBlankModule() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new AssemblyContext("d", "f.log", "s", "", CODE, "spring-boot-default", false));
    }

    @Test
    void rejectsBlankParserFormat() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new AssemblyContext("d", "f.log", "s", "m", CODE, null, false));
    }

    @Test
    void rejectsNullCode() {
        assertThatNullPointerException().isThrownBy(() ->
            new AssemblyContext("d", "f.log", "s", "m", null, "spring-boot-default", false));
    }
}
