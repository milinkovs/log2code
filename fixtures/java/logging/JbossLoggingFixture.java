package org.log2code.fixture.logging;

import org.jboss.logging.Logger;

public class JbossLoggingFixture {

    private static final Logger log = Logger.getLogger(JbossLoggingFixture.class);

    void plainMessage() {
        log.info("jboss plain info");
    }

    void plainWithThrowable(Throwable t) {
        log.error("jboss plain error", t);
    }

    void formatStyle(String name) {
        log.infof("jboss format style: %s", name);
    }

    void formatStyleWithThrowable(Throwable t, String reason) {
        log.errorf(t, "jboss format with cause: %s", reason);
    }

    void messageFormatStyle(String name) {
        log.tracev("jboss message format {0}", name);
    }
}
