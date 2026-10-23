package com.example.phonepayservice.exception;

public class MandateNotFoundException extends RuntimeException {
    public MandateNotFoundException(String message) {
        super(message);
    }
}
