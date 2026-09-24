package com.example.phonepayservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SavePayeeRequest(
        @NotNull(message = "Payee phone number is required")
        @Min(value = 6000000000L, message = "Invalid mobile number")
        @Max(value = 9999999999L, message = "Invalid mobile number")
        Long payeePhno,

        @Size(max = 50, message = "Nickname can be at most 50 characters")
        String nickname) {
}
