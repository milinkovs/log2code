package org.log2code.ingester;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The ingester's own version, baked into {@code ingester-version.properties} at build time. */
public final class IngesterVersion {

    private static final String RESOURCE = "/ingester-version.properties";
    private static final String FALLBACK = "dev";

    private IngesterVersion() {
    }

    public static String current() {
        Properties props = new Properties();
        try (InputStream in = IngesterVersion.class.getResourceAsStream(RESOURCE)) {
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
