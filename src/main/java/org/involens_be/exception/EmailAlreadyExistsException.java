package org.involens_be.exception;

public class EmailAlreadyExistsException extends RuntimeException {

    /** Creates the business error returned when an account email is already registered. */
    public EmailAlreadyExistsException() {
        super("Email is already registered");
    }
}
