package org.log2code.fixture.logging;

import org.springframework.core.log.LogAccessor;

public class LogAccessorFixture {

    private static final LogAccessor log = new LogAccessor(LogAccessorFixture.class);

    void messageOnly() {
        log.info("log accessor message");
    }

    void withThrowableFirst(Throwable cause) {
        log.error(cause, "log accessor failure");
    }

    void fatalLevel() {
        log.fatal("log accessor fatal");
    }
}
