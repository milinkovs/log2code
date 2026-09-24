package org.log2code.ingester.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 0.10 step 1, against real {@code logger_raw} values observed in {@code fixtures/logs/} (grepped
 * directly from the logger column of the real fixture files) and the real fully-qualified class names
 * they refer to - each pair checked against the actual dependency catalog this project analyzed (T14),
 * not guessed (two of {@code o.s.boot.reactor.netty.*} candidates that did not have a verifiable real
 * counterpart in the loaded catalog were deliberately left out).
 */
class LoggerResolverTest {

    /** service -> N(s), one class per case below plus a couple of unrelated decoys. */
    static Stream<Arguments> realAbbreviatedOrTruncatedCases() {
        return Stream.of(
            // abbreviated (0.10: same segment count, last segment equal, each earlier segment a prefix)
            Arguments.of("c.n.d.s.t.d.RedirectingEurekaHttpClient",
                "com.netflix.discovery.shared.transport.decorator.RedirectingEurekaHttpClient", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("c.n.d.s.t.d.RetryableEurekaHttpClient",
                "com.netflix.discovery.shared.transport.decorator.RetryableEurekaHttpClient", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("c.n.d.provider.DiscoveryJerseyProvider",
                "com.netflix.discovery.provider.DiscoveryJerseyProvider", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.s.cloud.context.scope.GenericScope",
                "org.springframework.cloud.context.scope.GenericScope", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.s.v.b.OptionalValidatorFactoryBean",
                "org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.apache.catalina.core.StandardEngine",
                "org.apache.catalina.core.StandardEngine", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.apache.catalina.core.StandardService",
                "org.apache.catalina.core.StandardService", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("d.c.s.b.c.m.assaults.ExceptionAssault",
                "de.codecentric.spring.boot.chaos.monkey.assaults.ExceptionAssault", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("i.m.o.c.ObservationThreadLocalAccessor",
                "io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.s.boot.reactor.netty.NettyWebServer",
                "org.springframework.boot.reactor.netty.NettyWebServer", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.s.d.j.r.query.QueryEnhancerFactories",
                "org.springframework.data.jpa.repository.query.QueryEnhancerFactories", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.s.c.n.e.s.EurekaAutoServiceRegistration",
                "org.springframework.cloud.netflix.eureka.serviceregistry.EurekaAutoServiceRegistration", LoggerResolver.Kind.ABBREV_UNIQUE),
            Arguments.of("o.s.c.g.r.RouteDefinitionRouteLocator",
                "org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator", LoggerResolver.Kind.ABBREV_UNIQUE),
            // truncated (left-cut to 40 chars, sometimes mid-segment or mid-classname - the real
            // %logger{39} field width from docs/log-format.md)
            Arguments.of(".s.d.r.c.RepositoryConfigurationDelegate",
                "org.springframework.data.repository.config.RepositoryConfigurationDelegate", LoggerResolver.Kind.TRUNCATED),
            Arguments.of("b.w.c.s.WebApplicationContextInitializer",
                "org.springframework.boot.web.context.servlet.WebApplicationContextInitializer", LoggerResolver.Kind.TRUNCATED),
            Arguments.of("b.w.a.e.AbstractErrorWebExceptionHandler",
                "org.springframework.boot.webflux.autoconfigure.error.AbstractErrorWebExceptionHandler", LoggerResolver.Kind.TRUNCATED),
            Arguments.of("eactorLoadBalancerExchangeFilterFunction",
                "org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction", LoggerResolver.Kind.TRUNCATED)
        );
    }

    @ParameterizedTest(name = "{0} -> {1} [{2}]")
    @MethodSource("realAbbreviatedOrTruncatedCases")
    void resolvesRealFixtureLoggerRawAgainstItsRealClass(String loggerRaw, String knownFqn, LoggerResolver.Kind expectedKind) {
        LoggerResolver resolver = new LoggerResolver(Map.of("svc", Set.of(
            knownFqn,
            "com.unrelated.other.SomeOtherClass",
            "org.unrelated.decoy.AnotherDecoyClass")));

        LoggerResolver.Resolution resolution = resolver.resolve(loggerRaw, "svc");

        assertThat(resolution.kind()).isEqualTo(expectedKind);
        assertThat(resolution.names()).containsExactly(knownFqn);
    }

    @Test
    void exactMatchWinsEvenWhenAnAbbreviatedCandidateAlsoExists() {
        LoggerResolver resolver = new LoggerResolver(Map.of("svc", Set.of(
            "com.netflix.discovery.DiscoveryClient",
            "com.other.netflix.discovery.DiscoveryClient")));

        LoggerResolver.Resolution resolution = resolver.resolve("com.netflix.discovery.DiscoveryClient", "svc");

        assertThat(resolution.kind()).isEqualTo(LoggerResolver.Kind.EXACT);
        assertThat(resolution.names()).containsExactly("com.netflix.discovery.DiscoveryClient");
    }

    @Test
    void ambiguousAbbreviationAcrossTwoCandidatesIsAbbrevMulti() {
        // Synthetic: "c.e.Handler" is a valid Logback abbreviation of both candidates at once.
        LoggerResolver resolver = new LoggerResolver(Map.of("svc", Set.of(
            "com.example.Handler", "com.exempt.Handler")));

        LoggerResolver.Resolution resolution = resolver.resolve("c.e.Handler", "svc");

        assertThat(resolution.kind()).isEqualTo(LoggerResolver.Kind.ABBREV_MULTI);
        assertThat(resolution.names()).containsExactlyInAnyOrder("com.example.Handler", "com.exempt.Handler");
    }

    @Test
    void unresolvableLoggerRawIsUnknown() {
        LoggerResolver resolver = new LoggerResolver(Map.of("svc", Set.of("com.example.Foo")));

        LoggerResolver.Resolution resolution = resolver.resolve("completely.unrelated.Name", "svc");

        assertThat(resolution.kind()).isEqualTo(LoggerResolver.Kind.UNKNOWN);
        assertThat(resolution.names()).isEmpty();
    }

    @Test
    void namesAreScopedPerService() {
        LoggerResolver resolver = new LoggerResolver(Map.of(
            "svc-a", Set.of("com.example.Foo"),
            "svc-b", Set.of("com.other.Bar")));

        assertThat(resolver.resolve("com.example.Foo", "svc-a").kind()).isEqualTo(LoggerResolver.Kind.EXACT);
        assertThat(resolver.resolve("com.example.Foo", "svc-b").kind()).isEqualTo(LoggerResolver.Kind.UNKNOWN);
    }

    @Test
    void unknownServiceOrBlankLoggerRawResolvesToUnknownWithoutThrowing() {
        LoggerResolver resolver = new LoggerResolver(Map.of("svc", Set.of("com.example.Foo")));

        assertThat(resolver.resolve("com.example.Foo", "no-such-service").kind()).isEqualTo(LoggerResolver.Kind.UNKNOWN);
        assertThat(resolver.resolve(null, "svc").kind()).isEqualTo(LoggerResolver.Kind.UNKNOWN);
        assertThat(resolver.resolve("", "svc").kind()).isEqualTo(LoggerResolver.Kind.UNKNOWN);
    }

    @Test
    void resultIsCachedPerServiceAndLoggerRawPair() {
        LoggerResolver resolver = new LoggerResolver(Map.of("svc", Set.of("com.example.Foo")));

        LoggerResolver.Resolution first = resolver.resolve("com.example.Foo", "svc");
        LoggerResolver.Resolution second = resolver.resolve("com.example.Foo", "svc");

        assertThat(first).isSameAs(second);
    }
}
