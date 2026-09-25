package org.log2code.api.web;

/** Generic 404 for a resource looked up by id (T24: catalog entry, source file, project method). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
