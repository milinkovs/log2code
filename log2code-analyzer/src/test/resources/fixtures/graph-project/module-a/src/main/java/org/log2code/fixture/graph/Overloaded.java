package org.log2code.fixture.graph;

public class Overloaded {

    public String describe(int value) {
        return "int:" + value;
    }

    public String describe(String value) {
        return "string:" + value;
    }

    public String describeBoth() {
        return describe(1) + describe("a");
    }
}
