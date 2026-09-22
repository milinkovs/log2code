package org.log2code.fixture.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

public class JulFixture {

    private static final Logger log = Logger.getLogger(JulFixture.class.getName());

    void convenienceMethod() {
        log.info("jul convenience info");
    }

    void logWithLiteralLevel() {
        log.log(Level.WARNING, "jul log with literal level");
    }

    void logWithThrowable(Throwable t) {
        log.log(Level.SEVERE, "jul severe with cause", t);
    }

    void logWithPlaceholderParam(String user) {
        log.log(Level.INFO, "jul with param: {0}", user);
    }

    void logpMessage(String cls, String method) {
        log.logp(Level.FINE, cls, method, "jul logp message");
    }
}
