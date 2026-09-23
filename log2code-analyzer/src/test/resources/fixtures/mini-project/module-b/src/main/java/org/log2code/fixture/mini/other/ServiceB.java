package org.log2code.fixture.mini.other;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceB {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceB.class);

    public void handle(String payload) {
        LOG.info("Handling {}", payload);
    }
}
