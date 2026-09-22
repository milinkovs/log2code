package org.log2code.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The analyzer's own version, baked into {@code analyzer-version.properties} at build time. */
public final class AnalyzerVersion {

    private static final String RESOURCE = "/analyzer-version.properties";
    private static final String FALLBACK = "dev";

    private AnalyzerVersion() {
    }

    public static String current() {
        Properties props = new Properties();
        try (InputStream in = AnalyzerVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return FALLBACK;
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String version = props.getProperty("version");
        return version == null || version.isBlank() || version.startsWith("${") ? FALLBACK : version;
    }
}
