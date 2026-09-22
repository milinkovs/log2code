package org.log2code.fixture.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jFluentFixture {

    private static final Logger log = LoggerFactory.getLogger(Slf4jFluentFixture.class);

    void logWithArgsDirectly(String name) {
        log.atInfo().log("processing {}", name);
    }

    void logWithSetMessage() {
        log.atWarn().setMessage("low disk space").log();
    }

    void logWithCauseAndArgument(Throwable cause, String id) {
        log.atError().setMessage("failed for {}").addArgument(id).setCause(cause).log();
    }
}
