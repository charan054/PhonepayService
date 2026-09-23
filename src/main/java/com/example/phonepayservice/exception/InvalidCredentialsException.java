package com.example.phonepayservice.exception;

// Wrong PIN, or no such phone number - deliberately the same exception/message for both, mirroring the bank's
// own login, so a caller can never tell "wrong PIN" apart from "no such account".
public class InvalidCredentialsException extends RuntimeException
{
    public InvalidCredentialsException(String message)
    {
        super(message);
    }
}
