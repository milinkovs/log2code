package org.log2code.fixture.mini;

public class ServiceA extends LoggingBase implements Greeter {

    public void process(int id, boolean flag) {
        log.info("Processing {}", id);
        if (flag) {
            log.info("Flag set for {}", id);
        } else {
            log.warn("Flag not set for {}", id);
        }
        for (int i = 0; i < id; i++) {
            log.debug("looping");
        }
        try {
            risky();
        } catch (IllegalStateException e) {
            log.error("Failed processing {}", id, e);
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                log.info("anon running");
            }
        };
        r.run();
    }

    public void ping(boolean first) {
        if (first) {
            log.info("ping");
        } else {
            log.info("ping");
        }
    }

    private void risky() {
        throw new IllegalStateException("boom");
    }

    @Override
    public String greet(String name) {
        log.info("Greeting {}", name);
        return "Hello " + name;
    }
}
