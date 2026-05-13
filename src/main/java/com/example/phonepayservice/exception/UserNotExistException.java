package com.example.phonepayservice.exception;

public class UserNotExistException extends RuntimeException
{
    public UserNotExistException(String message)
    {
        super(message);
    }
}
