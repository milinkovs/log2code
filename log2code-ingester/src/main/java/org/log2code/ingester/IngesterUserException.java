package org.log2code.ingester;

/**
 * A user/input error at the ingester CLI level (bad {@code --dataset}/{@code --at} argument, missing
 * config file) that is not already covered by a more specific exception ({@code ManifestException},
 * {@code LogFormatException}, {@code MatchingConfigException}, {@code CatalogLoadException}). Exits 1
 * (0.14), same as those.
 */
public final class IngesterUserException extends RuntimeException {

    public IngesterUserException(String message) {
        super(message);
    }

    public IngesterUserException(String message, Throwable cause) {
        super(message, cause);
    }
}
