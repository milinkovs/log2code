package org.log2code.fixture.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StructuralVarietyFixture {

    private static final Logger log = LoggerFactory.getLogger(StructuralVarietyFixture.class);

    static class NestedStatic {
        private static final Logger log = LoggerFactory.getLogger(NestedStatic.class);

        void logFromNested() {
            log.info("nested static class logging");
        }
    }

    void logFromAnonymousClass() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                // no logger field of its own: falls through to the enclosing class's field.
                log.info("anonymous class body, outer field");
            }
        };
        r.run();
    }

    void logFromLambda() {
        Runnable r = () -> log.info("lambda body, outer field");
        r.run();
    }

    void logFromVarLocal() {
        var localLog = LoggerFactory.getLogger(StructuralVarietyFixture.class);
        localLog.info("var-typed local logger");
    }

    enum Status {
        OK, FAILED;

        private static final Logger log = LoggerFactory.getLogger(Status.class);

        void logStatus() {
            log.info("enum body logging");
        }
    }

    record Summary(String text) {
        private static final Logger log = LoggerFactory.getLogger(Summary.class);

        void logSummary() {
            log.info("record body logging");
        }
    }
}
