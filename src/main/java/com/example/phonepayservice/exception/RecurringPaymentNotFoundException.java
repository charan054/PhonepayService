package com.example.phonepayservice.exception;

public class RecurringPaymentNotFoundException extends RuntimeException {
    public RecurringPaymentNotFoundException(String message) {
        super(message);
    }
}
