package org.log2code.fixture.graph;

public class RecordSink {

    public String store(String name, int count) {
        return name + count;
    }

    public String pick(String name) {
        return name;
    }

    public String pick(int count) {
        return String.valueOf(count);
    }
}
