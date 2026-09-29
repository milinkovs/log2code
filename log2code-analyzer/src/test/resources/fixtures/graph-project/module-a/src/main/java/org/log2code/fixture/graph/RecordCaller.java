package org.log2code.fixture.graph;

public class RecordCaller {

    private final RecordSink sink = new RecordSink();

    public String viaRecord(RecordRequest request) {
        return sink.store(request.name(), request.count());
    }

    public String ambiguousViaRecord(RecordRequest request) {
        return sink.pick(request.name());
    }
}
