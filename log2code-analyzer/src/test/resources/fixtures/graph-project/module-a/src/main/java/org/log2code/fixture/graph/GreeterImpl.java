package org.log2code.fixture.graph;

public class GreeterImpl implements Greeter {

    @Override
    public String greet(String name) {
        return "Hello " + name;
    }
}
