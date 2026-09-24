package org.log2code.ingester.match;

/** A user/config error in {@code config/matching.yml}: missing file or invalid YAML. */
public final class MatchingConfigException extends RuntimeException {

    public MatchingConfigException(String message) {
        super(message);
    }

    public MatchingConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
