package org.log2code.analyzer.template;

/**
 * Known values of the catalog's {@code unsupported_reason} field (0.7, T09 rule 9). A statement gets
 * one of these when its message shape cannot be turned into a template at all - it is still cataloged
 * (for coverage statistics), but skipped by the matcher (T20).
 *
 * <p>{@link #JBOSS_MESSAGE_LOGGER} is defined for schema completeness but is currently unreachable:
 * T08 (ADR-008) does not detect calls on a typed {@code @MessageLogger} interface as a {@code LogCall}
 * at all (that detection is explicitly left for the optional T36), so no call ever reaches T09 with
 * this shape yet.
 */
public final class UnsupportedReason {

    public static final String TOMCAT_STRING_MANAGER = "tomcat-string-manager";
    public static final String JBOSS_MESSAGE_LOGGER = "jboss-message-logger";
    public static final String UNKNOWN_SIGNATURE = "unknown-signature";

    private UnsupportedReason() {
    }
}
