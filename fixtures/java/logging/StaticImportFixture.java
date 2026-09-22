package org.log2code.fixture.logging;

import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;

public class StaticImportFixture {

    private static final Logger log = getLogger(StaticImportFixture.class);

    void messageViaStaticallyImportedFactory() {
        log.info("created via statically imported factory method");
    }
}

class StaticImportedConstantFixture {

    void messageViaStaticallyImportedConstant() {
        // LOG is a statically-imported field from an external holder class, not declared anywhere in
        // this code unit; its type is unresolvable, so this falls back to the heuristic (step 3.3).
        LOG.info("heuristic fallback: name matches the logger pattern");
    }
}
