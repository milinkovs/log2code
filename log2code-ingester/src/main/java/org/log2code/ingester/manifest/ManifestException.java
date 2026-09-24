package org.log2code.ingester.manifest;

/** A user/dataset error: missing or malformed {@code datasets/<id>/manifest.yml} (0.11). */
public final class ManifestException extends RuntimeException {

    public ManifestException(String message) {
        super(message);
    }

    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
