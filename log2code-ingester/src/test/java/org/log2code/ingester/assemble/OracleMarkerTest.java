package org.log2code.ingester.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.GroundTruth;

class OracleMarkerTest {

    @Test
    void stripsMarkerAndBuildsReliableGroundTruth() {
        OracleMarker.Result result = OracleMarker.strip(
            "@@L2C[org.springframework.samples.petclinic.customers.web.OwnerResource|updateOwner|89]@@ "
                + "Saving owner [Owner@1 id = 1]",
            UnreliableCallers.DEFAULT);

        assertThat(result).isNotNull();
        assertThat(result.remainder()).isEqualTo("Saving owner [Owner@1 id = 1]");
        GroundTruth gt = result.groundTruth();
        assertThat(gt.className()).isEqualTo("org.springframework.samples.petclinic.customers.web.OwnerResource");
        assertThat(gt.method()).isEqualTo("updateOwner");
        assertThat(gt.line()).isEqualTo(89);
        assertThat(gt.reliable()).isTrue();
    }

    @Test
    void stripsMarkerWithEmptyMessage() {
        OracleMarker.Result result = OracleMarker.strip(
            "@@L2C[org.apache.juli.logging.DirectJDKLog|log|170]@@ ", UnreliableCallers.DEFAULT);

        assertThat(result).isNotNull();
        assertThat(result.remainder()).isEmpty();
    }

    @Test
    void returnsNullWhenNoMarkerPresent() {
        assertThat(OracleMarker.strip("Saving owner [Owner@1 id = 1]", UnreliableCallers.DEFAULT)).isNull();
    }

    @Test
    void questionMarkFieldsMakeGroundTruthUnreliable() {
        OracleMarker.Result result = OracleMarker.strip("@@L2C[?|?|?]@@ some message", UnreliableCallers.DEFAULT);

        GroundTruth gt = result.groundTruth();
        assertThat(gt.className()).isNull();
        assertThat(gt.method()).isNull();
        assertThat(gt.line()).isNull();
        assertThat(gt.reliable()).isFalse();
    }

    @Test
    void emptyLineFieldMakesGroundTruthUnreliable() {
        OracleMarker.Result result = OracleMarker.strip("@@L2C[com.example.Foo|bar|]@@ msg", UnreliableCallers.DEFAULT);

        assertThat(result.groundTruth().line()).isNull();
        assertThat(result.groundTruth().reliable()).isFalse();
    }

    @Test
    void exactUnreliableCallerIsUnreliable() {
        OracleMarker.Result result = OracleMarker.strip(
            "@@L2C[org.springframework.core.log.LogAccessor|info|42]@@ msg", UnreliableCallers.DEFAULT);

        assertThat(result.groundTruth().reliable()).isFalse();
    }

    @Test
    void wildcardUnreliableCallerMatchesByPrefix() {
        OracleMarker.Result result = OracleMarker.strip(
            "@@L2C[org.apache.commons.logging.impl.Jdk14Logger|info|10]@@ msg", UnreliableCallers.DEFAULT);

        assertThat(result.groundTruth().reliable()).isFalse();
    }

    @Test
    void wildcardPatternDoesNotMatchUnrelatedPrefix() {
        // "org.apache.commons.logging.other.Foo" starts with the same characters as the wildcard
        // entry but is not actually inside that package — must not match.
        assertThat(UnreliableCallers.matches("org.apache.commons.loggingX.Foo", Set.of("org.apache.commons.logging.*")))
            .isFalse();
    }

    @Test
    void reliableCallerOutsideTheUnreliableListStaysReliable() {
        OracleMarker.Result result = OracleMarker.strip(
            "@@L2C[org.springframework.samples.petclinic.visits.web.VisitResource|saveVisit|63]@@ Saving visit",
            UnreliableCallers.DEFAULT);

        assertThat(result.groundTruth().reliable()).isTrue();
    }
}
