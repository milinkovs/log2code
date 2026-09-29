package org.log2code.fixture.graph;

/** A record: its accessors are implicit, which JavaParser's symbol solver cannot resolve (ADR-013). */
public record RecordRequest(String name, int count) {
}
