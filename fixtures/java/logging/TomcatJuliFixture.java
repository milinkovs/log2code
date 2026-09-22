package org.log2code.fixture.logging;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

public class TomcatJuliFixture {

    private static final Log log = LogFactory.getLog(TomcatJuliFixture.class);

    void messageOnly() {
        log.warn("tomcat juli warning");
    }

    void withThrowable(Throwable t) {
        log.error("tomcat juli error", t);
    }

    void fatalLevel() {
        log.fatal("tomcat juli fatal");
    }
}
