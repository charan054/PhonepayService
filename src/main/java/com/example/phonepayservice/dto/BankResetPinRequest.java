package com.example.phonepayservice.dto;

// What we send to the bank's own POST /bank/forgotpin/reset.
public record BankResetPinRequest(long phno, String otp, String newPin) {
}
