package org.log2code.analyzer.deps;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/**
 * The 0.10-step-1 matching rule (T12), verified against real {@code logger_raw} values observed in
 * {@code fixtures/logs/} and the real fully-qualified class names they refer to (checked directly against
 * the actual dependency jars in {@code ~/.m2}, not guessed).
 */
class LoggerNameMatcherTest {

    static Stream<Arguments> realCases() {
        return Stream.of(
            // exact: logger_raw short enough that Logback never abbreviated it
            Arguments.of("com.zaxxer.hikari.HikariDataSource", "com.zaxxer.hikari.HikariDataSource", LoggerNameMatcher.Kind.EXACT),
            Arguments.of("com.zaxxer.hikari.pool.HikariPool", "com.zaxxer.hikari.pool.HikariPool", LoggerNameMatcher.Kind.EXACT),
            Arguments.of("com.netflix.discovery.DiscoveryClient", "com.netflix.discovery.DiscoveryClient", LoggerNameMatcher.Kind.EXACT),
            // abbreviated: same segment count, last segment equal, each earlier segment a prefix
            Arguments.of("o.s.web.servlet.DispatcherServlet", "org.springframework.web.servlet.DispatcherServlet", LoggerNameMatcher.Kind.ABBREVIATED),
            Arguments.of("c.n.discovery.InstanceInfoReplicator", "com.netflix.discovery.InstanceInfoReplicator", LoggerNameMatcher.Kind.ABBREVIATED),
            Arguments.of("o.s.s.p.customers.web.OwnerResource",
                "org.springframework.samples.petclinic.customers.web.OwnerResource", LoggerNameMatcher.Kind.ABBREVIATED),
            Arguments.of("o.s.s.p.visits.web.VisitResource",
                "org.springframework.samples.petclinic.visits.web.VisitResource", LoggerNameMatcher.Kind.ABBREVIATED),
            // truncated: left-truncated to 40 chars, sometimes mid-segment or mid-classname
            Arguments.of(".w.s.m.s.DefaultHandlerExceptionResolver",
                "org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver", LoggerNameMatcher.Kind.TRUNCATED),
            Arguments.of("trationDelegate$BeanPostProcessorChecker",
                "org.springframework.context.support.PostProcessorRegistrationDelegate$BeanPostProcessorChecker", LoggerNameMatcher.Kind.TRUNCATED),
            Arguments.of("iguration$LoadBalancerCaffeineWarnLogger",
                "org.springframework.cloud.loadbalancer.config.LoadBalancerCacheAutoConfiguration$LoadBalancerCaffeineWarnLogger",
                LoggerNameMatcher.Kind.TRUNCATED),
            Arguments.of("j.LocalContainerEntityManagerFactoryBean",
                "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean", LoggerNameMatcher.Kind.TRUNCATED),
            // none: unrelated real classes
            Arguments.of("com.zaxxer.hikari.HikariDataSource", "org.springframework.web.servlet.DispatcherServlet", LoggerNameMatcher.Kind.NONE),
            Arguments.of("o.s.web.servlet.DispatcherServlet",
                "org.springframework.samples.petclinic.customers.web.OwnerResource", LoggerNameMatcher.Kind.NONE),
            Arguments.of("org.hibernate.orm.core", "org.hibernate.engine.jdbc.env.spi.JdbcEnvironment", LoggerNameMatcher.Kind.NONE),
            // none: too short a fragment to trust as a truncated suffix, even though it IS a literal suffix
            Arguments.of("Pool", "com.zaxxer.hikari.pool.HikariPool", LoggerNameMatcher.Kind.NONE)
        );
    }

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @MethodSource("realCases")
    void classifiesRealLoggerAndClassPairs(String loggerRaw, String candidateFqn, LoggerNameMatcher.Kind expected) {
        assertThat(LoggerNameMatcher.classify(loggerRaw, candidateFqn)).isEqualTo(expected);
        assertThat(LoggerNameMatcher.matches(loggerRaw, candidateFqn)).isEqualTo(expected != LoggerNameMatcher.Kind.NONE);
    }

    @Test
    void tomcatContainerHierarchyNamesDoNotLookLikeClassNames() {
        assertThat(LoggerNameMatcher.looksLikeClassName("o.a.c.c.C.[Tomcat].[localhost].[/]")).isFalse();
        assertThat(LoggerNameMatcher.looksLikeClassName("o.a.c.c.C.[.[.[/].[dispatcherServlet]")).isFalse();
    }

    @Test
    void hibernateCategoryStringsDoLookLikeClassNames() {
        // Shaped like a class FQN (only identifier chars and dots), even though no class named "core"/"jpa"
        // actually exists in org.hibernate.orm.* (Hibernate's @MessageLogger categories are hand-picked
        // strings, not tied to a package) - this is exactly why AC4's denominator excludes only the
        // structurally-impossible (bracketed) names, not these.
        assertThat(LoggerNameMatcher.looksLikeClassName("org.hibernate.orm.core")).isTrue();
        assertThat(LoggerNameMatcher.looksLikeClassName("org.hibernate.orm.connections.pooling")).isTrue();
    }

    @Test
    void blankOrNullInputsNeverMatch() {
        assertThat(LoggerNameMatcher.classify(null, "a.b.C")).isEqualTo(LoggerNameMatcher.Kind.NONE);
        assertThat(LoggerNameMatcher.classify("a.b.C", null)).isEqualTo(LoggerNameMatcher.Kind.NONE);
        assertThat(LoggerNameMatcher.classify("", "a.b.C")).isEqualTo(LoggerNameMatcher.Kind.NONE);
        assertThat(LoggerNameMatcher.looksLikeClassName(null)).isFalse();
        assertThat(LoggerNameMatcher.looksLikeClassName("")).isFalse();
    }
}
