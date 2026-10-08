package org.log2code.eval;

/** A user or input error (bad dataset, missing analysis run): the CLI exits with 1 (0.14). */
public final class EvalUserException extends RuntimeException {

    public EvalUserException(String message) {
        super(message);
    }
}
