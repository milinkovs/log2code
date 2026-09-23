package org.log2code.fixture.graph;

public class GraphC {

    private final String label;

    public GraphC(String label) {
        this.label = label;
    }

    @Deprecated
    public void methodC() {
        // leaf of the A -> B -> C chain
    }
}
