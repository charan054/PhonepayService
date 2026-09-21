package com.example.phonepayservice.exception;

/**
 * The request may have reached the bank but no clear answer came back (timeout, server error). The money movement
 * may or may not have happened, so it must NEVER be refunded or retried automatically; a person has to check.
 */
public class BankOutcomeUnknownException extends RuntimeException {
    public BankOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
