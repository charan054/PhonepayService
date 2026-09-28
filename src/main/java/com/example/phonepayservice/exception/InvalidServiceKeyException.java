package com.example.phonepayservice.exception;

public class InvalidServiceKeyException extends RuntimeException {
    public InvalidServiceKeyException(String message) {
        super(message);
    }
}
