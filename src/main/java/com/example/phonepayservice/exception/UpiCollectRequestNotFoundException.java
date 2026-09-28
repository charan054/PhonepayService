package com.example.phonepayservice.exception;

public class UpiCollectRequestNotFoundException extends RuntimeException {
    public UpiCollectRequestNotFoundException(String message) {
        super(message);
    }
}
