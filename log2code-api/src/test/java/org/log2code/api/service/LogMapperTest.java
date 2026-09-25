package org.log2code.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.log2code.api.dto.LogDetail;
import org.log2code.api.dto.LogSummary;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.CodeVersion;
import org.log2code.core.model.EnrichedLog;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.GroundTruth;
import org.log2code.core.model.Level;
import org.log2code.core.model.MatchResult;
import org.log2code.core.model.StackFrame;

class LogMapperTest {

    @Test
    void toSummaryTruncatesMessageAt500Characters() {
        String longMessage = "x".repeat(600);
        EnrichedLog log = minimalLog(longMessage, null, null, null);

        LogSummary summary = LogMapper.toSummary(log);

        assertThat(summary.message()).hasSize(LogMapper.SUMMARY_MESSAGE_MAX_LENGTH);
        assertThat(summary.message()).isEqualTo("x".repeat(500));
    }

    @Test
    void toSummaryToleratesNullMessageWithoutThrowing() {
        EnrichedLog log = minimalLog(null, null, null, null);

        LogSummary summary = LogMapper.toSummary(log);

        assertThat(summary.message()).isNull();
    }

    @Test
    void toSummaryLeavesShortMessageUntouchedAndDenormalizesFromMatch() {
        MatchResult match = matchResult();
        EnrichedLog log = minimalLog("short", match, null, null);

        LogSummary summary = LogMapper.toSummary(log);

        assertThat(summary.message()).isEqualTo("short");
        assertThat(summary.status()).isEqualTo("matched");
        assertThat(summary.confidenceLevel()).isEqualTo("high");
        assertThat(summary.classFqn()).isEqualTo("org.example.OwnerResource");
        assertThat(summary.methodName()).isEqualTo("updateOwner");
        assertThat(summary.line()).isEqualTo(89);
        assertThat(summary.hasException()).isFalse();
    }

    @Test
    void toSummaryHandlesNullMatchAndMarksExceptionPresence() {
        ExceptionInfo exception = new ExceptionInfo("java.lang.RuntimeException", "java.lang.RuntimeException",
            "boom", List.of(), List.of());
        EnrichedLog log = minimalLog("boom", null, exception, null);

        LogSummary summary = LogMapper.toSummary(log);

        assertThat(summary.status()).isNull();
        assertThat(summary.confidence()).isNull();
        assertThat(summary.classFqn()).isNull();
        assertThat(summary.hasException()).isTrue();
    }

    @Test
    void toDetailMapsNestedExceptionMatchAndGroundTruth() {
        StackFrame frame = new StackFrame("org.example.Foo", "bar", "Foo.java", 12, true, "petclinic", "file-1", "https://example.invalid");
        CausedBy causedBy = new CausedBy("java.lang.IllegalStateException", "nested", List.of(frame));
        ExceptionInfo exception = new ExceptionInfo("java.lang.RuntimeException", "java.lang.IllegalStateException",
            "boom", List.of(frame), List.of(causedBy));
        GroundTruth groundTruth = new GroundTruth("org.example.Foo", "bar", 12, true);
        MatchResult match = matchResult();
        EnrichedLog log = minimalLog("boom", match, exception, groundTruth);

        LogDetail detail = LogMapper.toDetail(log);

        assertThat(detail.logId()).isEqualTo(log.logId());
        assertThat(detail.match().statementId()).isEqualTo("stmt-1");
        assertThat(detail.match().candidates()).hasSize(1);
        assertThat(detail.exception().frames()).hasSize(1);
        assertThat(detail.exception().frames().get(0).className()).isEqualTo("org.example.Foo");
        assertThat(detail.exception().causedBy()).hasSize(1);
        assertThat(detail.exception().causedBy().get(0).frames()).hasSize(1);
        assertThat(detail.groundTruth().reliable()).isTrue();
        assertThat(detail.code().name()).isEqualTo("petclinic");
    }

    @Test
    void toDetailToleratesAllOptionalFieldsBeingNull() {
        EnrichedLog log = minimalLog("no frills", null, null, null);

        LogDetail detail = LogMapper.toDetail(log);

        assertThat(detail.match()).isNull();
        assertThat(detail.exception()).isNull();
        assertThat(detail.groundTruth()).isNull();
        assertThat(detail.code().name()).isEqualTo("petclinic");
    }

    private static MatchResult matchResult() {
        return new MatchResult("matched", "stmt-1", 0.9, "high",
            List.of(new org.log2code.core.model.Candidate("stmt-1", 0.9)), Map.of("regex_full", 0.45),
            List.of("Owner[1]"), "petclinic", "customers-service", "org.example.OwnerResource", "updateOwner",
            "path", "slf4j", "placeholders", 89, "Saving owner {}", "https://example.invalid");
    }

    private static EnrichedLog minimalLog(String message, MatchResult match, ExceptionInfo exception, GroundTruth groundTruth) {
        return new EnrichedLog("log-1", Instant.parse("2026-09-24T10:00:00Z"), "2026-09-24T10:00:00Z", "smoke-01",
            "logs/customers-service.log", 42, 1, 0, "customers-service", "spring-petclinic-customers-service",
            "app", "1", "thread-1", Level.INFO, "o.s.s.p.c.web.OwnerResource",
            "org.springframework.samples.petclinic.customers.web.OwnerResource", message, "raw " + message,
            null, null, exception, new CodeVersion("petclinic", "v1"), match, groundTruth, "spring-boot-default",
            "1.0.0", Instant.parse("2026-09-24T10:00:01Z"));
    }
}
