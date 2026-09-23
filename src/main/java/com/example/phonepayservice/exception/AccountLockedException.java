package com.example.phonepayservice.exception;

// The bank itself has locked this account after too many wrong PINs; relayed as-is (see BankGateway.login).
public class AccountLockedException extends RuntimeException
{
    public AccountLockedException(String message)
    {
        super(message);
    }
}
