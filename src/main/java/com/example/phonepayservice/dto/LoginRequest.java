package com.example.phonepayservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(
        @NotNull(message = "Phone number is required")
        @Min(value = 6000000000L, message = "Invalid mobile number")
        @Max(value = 9999999999L, message = "Invalid mobile number")
        Long phno,
        @NotBlank(message = "PIN is required")
        String pin) {
}
