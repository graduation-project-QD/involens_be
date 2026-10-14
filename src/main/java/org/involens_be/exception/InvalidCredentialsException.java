package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidCredentialsException extends RuntimeException {

    /** Reports an unsuccessful login without exposing which credential was incorrect. */
    public InvalidCredentialsException() {
        this("Email or password is incorrect");
        log.debug("Invalid login credentials reported");
    }

    /** Reports an authentication failure with the approved account-availability message. */
    public InvalidCredentialsException(String message) {
        super(message);
        log.debug("Authentication failure reported: {}", message);
    }
}
