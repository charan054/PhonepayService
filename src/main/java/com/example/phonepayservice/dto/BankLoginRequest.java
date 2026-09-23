package com.example.phonepayservice.dto;

// What we send to the bank's own POST /bank/login: this call IS the credential check, so a wrong pin fails here.
public record BankLoginRequest(long phno, String pin) {
}
