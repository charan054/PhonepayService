package com.example.phonepayservice.exception;

/** The bank refused because another request touched the same account at the same moment. Nothing was changed; retrying is safe. */
public class BankConflictException extends RuntimeException {
    public BankConflictException(String message) {
        super(message);
    }
}
