package org.log2code.fixture.logging;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class JclFixture {

    private static final Log log = LogFactory.getLog(JclFixture.class);

    void messageOnly() {
        log.info("starting");
    }

    void withThrowable() {
        try {
            risky();
        } catch (Exception ex) {
            log.error("failed", ex);
        }
    }

    void fatalLevel() {
        log.fatal("unrecoverable");
    }

    private void risky() {
    }
}

abstract class JclAbstractBase {

    protected final Log logger = LogFactory.getLog(getClass());

    void logFromBase() {
        logger.debug("base says hello");
    }
}

class JclSubclass extends JclAbstractBase {

    void logFromSubclass() {
        logger.info("subclass says hello too");
    }
}
