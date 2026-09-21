package com.example.phonepayservice.exception;

/** The payment did not complete. The message tells the user what happened to their money. */
public class TransferFailedException extends RuntimeException {
    public TransferFailedException(String message) {
        super(message);
    }
}
