package org.log2code.analyzer;

/**
 * A user/input error (0.14 exit code 1): bad config, a git repository that cannot be read,
 * an unknown index name, and similar. {@link AnalyzerCli} maps this to exit code 1; any other
 * exception is treated as an internal error (exit code 2).
 */
public final class CliUserException extends RuntimeException {

    public CliUserException(String message) {
        super(message);
    }

    public CliUserException(String message, Throwable cause) {
        super(message, cause);
    }
}
