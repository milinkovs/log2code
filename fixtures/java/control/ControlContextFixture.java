package org.log2code.fixture.control;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One log call per method, each exercising exactly one T11 golden scenario (see
 * {@code ControlContextGoldenTest} and {@code src/test/resources/golden/control/<method>.json}).
 * Not compiled - JavaParser only needs valid syntax, like the other {@code fixtures/java/} fixtures.
 */
public class ControlContextFixture {

    private static final Logger log = LoggerFactory.getLogger(ControlContextFixture.class);

    void logAtMethodStart(Owner owner) {
        log.info("starting for {}", owner);
    }

    void guardClause(Owner owner) {
        Owner normalized = normalize(owner);
        if (normalized == null) {
            return;
        }
        log.info("processing owner {}", normalized);
    }

    void nestedIfElse(Owner owner) {
        if (owner.isActive()) {
            if (owner.getPets().isEmpty()) {
                ownerRepository.flagEmpty(owner);
            } else {
                log.warn("owner {} has pets after all", owner);
            }
        }
    }

    void logInCatch() {
        try {
            riskyCall();
        } catch (IllegalStateException e) {
            cleanup();
            log.error("call failed", e);
        }
    }

    void loopWithBreak(List<Owner> owners) {
        for (Owner owner : owners) {
            if (owner == null) {
                break;
            }
            log.info("visiting owner {}", owner);
        }
    }

    void switchCase(int status) {
        switch (status) {
            case 1 -> ownerRepository.markPending();
            case 2 -> log.info("status is two");
        }
    }

    void switchDefaultCase(int status) {
        switch (status) {
            case 1 -> ownerRepository.markPending();
            default -> log.warn("unexpected status {}", status);
        }
    }

    void logInLambda(List<Owner> owners) {
        owners.forEach(owner -> {
            log.info("visiting {}", owner);
        });
    }

    private Owner normalize(Owner owner) {
        return owner;
    }

    private void riskyCall() {
    }

    private void cleanup() {
    }
}
