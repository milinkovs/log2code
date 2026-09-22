package org.log2code.fixture.logging;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jFixture {

    private static final Logger log = LoggerFactory.getLogger(Slf4jFixture.class);

    void plainMessage() {
        log.info("starting up");
    }

    void withPlaceholder(String name) {
        log.debug("processing {}", name);
    }

    void withTwoPlaceholders(String a, String b) {
        log.warn("mismatch: expected {} but got {}", a, b);
    }

    void withCaughtException() {
        try {
            riskyCall();
        } catch (IllegalStateException e) {
            log.error("call failed", e);
        }
    }

    void withNewExceptionArgument() {
        log.error("validation failed", new IllegalArgumentException("bad input"));
    }

    void placeholderNotConfusedWithThrowable(Exception cause) {
        // one placeholder, one extra arg that fills it -> NOT a throwable, even though the name matches
        // the exception-like-name set (the hole count is checked first).
        log.info("cause was {}", cause);
    }

    void slf4jHasNoFatalLevel() {
        // org.slf4j.Logger has no fatal(...) method, unlike JCL/Log4j2/JBoss - must not be detected.
        log.fatal("this method does not exist on org.slf4j.Logger");
    }

    // --- step 6 negative cases: not log calls ---

    void guardedByEnabledCheck() {
        if (log.isDebugEnabled()) {
            log.debug("expensive: " + computeExpensive());
        }
    }

    void unrelatedReceiverNamedList() {
        List<String> list = new ArrayList<>();
        list.info("this is not a logger");
    }

    void loggerNameIsNotALogCall() {
        String name = log.getName();
    }

    private void riskyCall() {
    }

    private String computeExpensive() {
        return "x";
    }
}
