package com.example.phonepayservice.dto;

// What we send to the bank's own POST /bank/forgotpin/request.
public record BankForgotPinRequest(long phno) {
}
