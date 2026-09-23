package org.log2code.fixture.graph;

public class Recursive {

    public int factorial(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * factorial(n - 1);
    }
}
