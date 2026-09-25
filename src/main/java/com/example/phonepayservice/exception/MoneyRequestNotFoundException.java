package com.example.phonepayservice.exception;

public class MoneyRequestNotFoundException extends RuntimeException {
    public MoneyRequestNotFoundException(String message) {
        super(message);
    }
}
