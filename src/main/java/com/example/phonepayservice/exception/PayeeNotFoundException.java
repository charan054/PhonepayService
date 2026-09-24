package com.example.phonepayservice.exception;

public class PayeeNotFoundException extends RuntimeException {
    public PayeeNotFoundException(String message) {
        super(message);
    }
}
