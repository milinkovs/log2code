package org.log2code.fixture.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Log4j2Fixture {

    private static final Logger log = LogManager.getLogger(Log4j2Fixture.class);

    void plainMessage() {
        log.info("log4j2 info");
    }

    void withPlaceholder(String name) {
        log.debug("log4j2 processing {}", name);
    }

    void withThrowable(Exception exc) {
        log.error("log4j2 failure", exc);
    }

    void fatalLevel() {
        log.fatal("log4j2 fatal");
    }

    void logWithLevelArg() {
        log.log(Level.WARN, "log4j2 generic log method");
    }
}
