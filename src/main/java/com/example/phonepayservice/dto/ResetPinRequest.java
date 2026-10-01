package com.example.phonepayservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ResetPinRequest(
        @NotNull(message = "Phone number is required")
        @Min(value = 6000000000L, message = "Invalid mobile number")
        @Max(value = 9999999999L, message = "Invalid mobile number")
        Long phno,
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
        String otp,
        @NotBlank(message = "New PIN is required")
        @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits")
        String newPin) {
}
