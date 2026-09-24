package com.example.phonepayservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// Only a 4-6 digit PIN can ever have been set on the bank side, so anything else is rejected here rather than
// being relayed on to the bank - an unbounded PIN string would otherwise trip BCrypt's 72-byte input limit
// there and come back as a bare 500, which BankGateway.login() then turns into a misleading 503.
public record LoginRequest(
        @NotNull(message = "Phone number is required")
        @Min(value = 6000000000L, message = "Invalid mobile number")
        @Max(value = 9999999999L, message = "Invalid mobile number")
        Long phno,
        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits")
        String pin) {
}
