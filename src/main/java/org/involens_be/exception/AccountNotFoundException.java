package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AccountNotFoundException extends RuntimeException {
    /** Reports that the requested account no longer exists. */
    public AccountNotFoundException() {
        super("Account not found");
        log.warn("Requested account was not found");
    }
}
