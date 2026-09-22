package org.log2code.fixture.logging;

import java.lang.System.Logger.Level;

public class SystemLoggerFixture {

    private final System.Logger log = System.getLogger(SystemLoggerFixture.class.getName());

    void plainMessage() {
        log.log(Level.INFO, "system logger info");
    }

    void withThrowable(Throwable cause) {
        log.log(Level.ERROR, "system logger error", cause);
    }

    void withDynamicLevel(Level level) {
        log.log(level, "system logger with a runtime level");
    }
}
