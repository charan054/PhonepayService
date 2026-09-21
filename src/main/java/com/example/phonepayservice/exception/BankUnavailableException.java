package com.example.phonepayservice.exception;

/** The request never reached the bank (or it was a read-only call). Nothing was changed. */
public class BankUnavailableException extends RuntimeException {
    public BankUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
