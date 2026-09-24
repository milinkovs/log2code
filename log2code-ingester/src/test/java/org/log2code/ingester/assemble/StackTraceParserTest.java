package org.log2code.ingester.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.log2code.core.model.CausedBy;
import org.log2code.core.model.ExceptionInfo;
import org.log2code.core.model.StackFrame;

class StackTraceParserTest {

    @Test
    void classLineFollowedByFrames() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "java.lang.RuntimeException: Chaos Monkey - RuntimeException",
            "\tat com.example.Foo.bar(Foo.java:42)",
            "\tat com.example.Baz.qux(Baz.java:7)"));

        assertThat(info.className()).isEqualTo("java.lang.RuntimeException");
        assertThat(info.message()).isEqualTo("Chaos Monkey - RuntimeException");
        assertThat(info.rootClass()).isEqualTo("java.lang.RuntimeException");
        assertThat(info.causedBy()).isEmpty();
        assertThat(info.frames()).hasSize(2);
        StackFrame f0 = info.frames().get(0);
        assertThat(f0.className()).isEqualTo("com.example.Foo");
        assertThat(f0.method()).isEqualTo("bar");
        assertThat(f0.file()).isEqualTo("Foo.java");
        assertThat(f0.line()).isEqualTo(42);
        assertThat(f0.inProject()).isFalse();
    }

    @Test
    void classLineWithoutMessage() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "java.lang.NullPointerException",
            "\tat com.example.Foo.bar(Foo.java:1)"));

        assertThat(info.className()).isEqualTo("java.lang.NullPointerException");
        assertThat(info.message()).isNull();
    }

    @Test
    void nativeMethodAndUnknownSourceHaveNoFileOrLine() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "java.lang.RuntimeException: x",
            "\tat java.base/jdk.internal.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)",
            "\tat java.base/java.lang.reflect.Constructor.newInstanceWithCaller(Constructor.java:502)",
            "\tat com.example.Foo.bar(Unknown Source)"));

        assertThat(info.frames()).hasSize(3);
        StackFrame native0 = info.frames().get(0);
        assertThat(native0.className()).isEqualTo("jdk.internal.reflect.NativeConstructorAccessorImpl");
        assertThat(native0.method()).isEqualTo("newInstance0");
        assertThat(native0.file()).isNull();
        assertThat(native0.line()).isNull();

        StackFrame moduleFrame = info.frames().get(1);
        assertThat(moduleFrame.className()).isEqualTo("java.lang.reflect.Constructor");
        assertThat(moduleFrame.method()).isEqualTo("newInstanceWithCaller");
        assertThat(moduleFrame.file()).isEqualTo("Constructor.java");
        assertThat(moduleFrame.line()).isEqualTo(502);

        StackFrame unknownSource = info.frames().get(2);
        assertThat(unknownSource.file()).isNull();
        assertThat(unknownSource.line()).isNull();
    }

    @Test
    void jarSuffixIsStrippedWithAndWithoutTilde() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "java.lang.RuntimeException: x",
            "\tat org.springframework.samples.petclinic.customers.web.OwnerResource.updateOwner"
                + "(OwnerResource.java:89) ~[classes/:na]",
            "\tat org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1683)"
                + " [tomcat-embed-core-11.0.11.jar:11.0.11]"));

        assertThat(info.frames()).hasSize(2);
        assertThat(info.frames().get(0).className())
            .isEqualTo("org.springframework.samples.petclinic.customers.web.OwnerResource");
        assertThat(info.frames().get(0).method()).isEqualTo("updateOwner");
        assertThat(info.frames().get(0).line()).isEqualTo(89);
        assertThat(info.frames().get(1).className()).isEqualTo("org.apache.tomcat.util.net.NioEndpoint$SocketProcessor");
        assertThat(info.frames().get(1).line()).isEqualTo(1683);
    }

    @Test
    void causedByChainWithMoreLine() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "org.springframework.web.client.ResourceAccessException: I/O error",
            "\tat org.springframework.web.client.RestClient.execute(RestClient.java:1)",
            "\tat org.springframework.web.client.RestClient.execute(RestClient.java:2)",
            "Caused by: org.apache.hc.client5.http.HttpHostConnectException: Connect to "
                + "http://discovery-server:8761 failed: Connection refused",
            "\tat java.base/sun.nio.ch.Net.pollConnect(Native Method)",
            "\tat java.base/sun.nio.ch.Net.pollConnectNow(Net.java:684)",
            "\t... 25 more"));

        assertThat(info.frames()).hasSize(2);
        assertThat(info.causedBy()).hasSize(1);
        CausedBy cause = info.causedBy().get(0);
        assertThat(cause.className()).isEqualTo("org.apache.hc.client5.http.HttpHostConnectException");
        assertThat(cause.message()).isEqualTo("Connect to http://discovery-server:8761 failed: Connection refused");
        assertThat(cause.frames()).hasSize(2);
        assertThat(info.rootClass()).isEqualTo("org.apache.hc.client5.http.HttpHostConnectException");
    }

    @Test
    void causedByChainWithCommonFramesOmittedLine() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "jakarta.ws.rs.ProcessingException: java.net.SocketTimeoutException: Read timed out",
            "\tat org.glassfish.jersey.apache.connector.ApacheConnector.apply(ApacheConnector.java:535)"
                + " ~[jersey-apache-connector-3.0.5.jar:na]",
            "Caused by: java.net.SocketTimeoutException: Read timed out",
            "\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288) ~[na:na]",
            "\t... 16 common frames omitted"));

        assertThat(info.frames()).hasSize(1);
        assertThat(info.causedBy()).hasSize(1);
        assertThat(info.causedBy().get(0).frames()).hasSize(1);
        assertThat(info.rootClass()).isEqualTo("java.net.SocketTimeoutException");
    }

    @Test
    void twoLevelCausedByChain() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "com.example.OuterException: outer",
            "\tat com.example.A.a(A.java:1)",
            "Caused by: com.example.MiddleException: middle",
            "\tat com.example.B.b(B.java:2)",
            "Caused by: com.example.InnerException: inner",
            "\tat com.example.C.c(C.java:3)"));

        assertThat(info.causedBy()).hasSize(2);
        assertThat(info.causedBy().get(0).className()).isEqualTo("com.example.MiddleException");
        assertThat(info.causedBy().get(1).className()).isEqualTo("com.example.InnerException");
        assertThat(info.rootClass()).isEqualTo("com.example.InnerException");
    }

    @Test
    void noClassLineWhenFramesStartDirectly() {
        // docs/log-format.md §4: RedirectingEurekaHttpClient-style event — the message already
        // embeds the exception description, and frames follow with no separate class line.
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "\tat org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec"
                + ".createResourceAccessException(DefaultRestClient.java:763)",
            "\tat org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec"
                + ".exchangeInternal(DefaultRestClient.java:615)"));

        assertThat(info.className()).isNull();
        assertThat(info.message()).isNull();
        assertThat(info.rootClass()).isNull();
        assertThat(info.frames()).hasSize(2);
    }

    @Test
    void reactorCheckpointBlockIsNotParsedIntoFramesOrCauses() {
        ExceptionInfo info = StackTraceParser.parse(List.of(
            "org.springframework.web.reactive.function.client.WebClientResponseException$InternalServerError: "
                + "500 Internal Server Error from GET http://172.20.0.8:8081/owners/1",
            "\tat org.springframework.web.reactive.function.client.WebClientResponseException.create"
                + "(WebClientResponseException.java:319)",
            "\tSuppressed: The stacktrace has been enhanced by Reactor, refer to additional information below:",
            "Error has been observed at the following site(s):",
            "\t*__checkpoint ⇢ 500 INTERNAL_SERVER_ERROR from GET http://customers-service/owners/1 [DefaultWebClient]",
            "\t*__checkpoint ⇢ Handler org.springframework.samples.petclinic.api.boundary.web.ApiGatewayController"
                + "#getOwnerDetails(int) [DispatcherHandler]",
            "Original Stack Trace:",
            "\t\tat org.springframework.web.reactive.function.client.WebClientResponseException.create"
                + "(WebClientResponseException.java:319)"));

        assertThat(info.frames()).hasSize(1);
        assertThat(info.causedBy()).isEmpty();
        assertThat(info.rootClass())
            .isEqualTo("org.springframework.web.reactive.function.client.WebClientResponseException$InternalServerError");
    }
}
