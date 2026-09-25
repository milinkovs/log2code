package org.log2code.api.web;

/** Thrown by {@link LogsController#get} when {@code logId} has no document in {@code log2code-logs}. */
public class LogNotFoundException extends RuntimeException {

    public LogNotFoundException(String logId) {
        super("log not found: " + logId);
    }
}
