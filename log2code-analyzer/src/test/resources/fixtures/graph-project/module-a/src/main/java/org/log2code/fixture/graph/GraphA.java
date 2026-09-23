package org.log2code.fixture.graph;

/** A -> B -> C chain: methodA is the "entry point" (nothing else in this fixture calls it). */
public class GraphA {

    private final GraphB b = new GraphB();

    public void methodA() {
        b.methodB();
    }
}
