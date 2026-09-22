package org.log2code.analyzer.logging;

/** Known values of the catalog's {@code logging_api} field (0.7). */
public final class LoggingApi {

    public static final String SLF4J = "slf4j";
    public static final String JCL = "jcl";
    public static final String SPRING_LOG_ACCESSOR = "spring-log-accessor";
    public static final String JUL = "jul";
    public static final String LOG4J2 = "log4j2";
    public static final String SYSTEM_LOGGER = "system-logger";
    public static final String JBOSS_LOGGING = "jboss-logging";
    public static final String TOMCAT_JULI = "tomcat-juli";
    public static final String UNKNOWN = "unknown";

    private LoggingApi() {
    }
}
