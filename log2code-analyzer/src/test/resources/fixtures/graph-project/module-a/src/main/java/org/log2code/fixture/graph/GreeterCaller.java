package org.log2code.fixture.graph;

/** Calls through the interface type, not the concrete one, so the call resolves to {@code
 * Greeter.greet} (no body in this project) - the "interface with exactly one implementation" case. */
public class GreeterCaller {

    public String callGreeter() {
        Greeter g = new GreeterImpl();
        return g.greet("world");
    }
}
