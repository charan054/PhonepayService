package com.example.phonepayservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateMoneyRequestRequest(
        @NotNull(message = "Payer phone number is required")
        @Min(value = 6000000000L, message = "Invalid mobile number")
        @Max(value = 9999999999L, message = "Invalid mobile number")
        Long payerPhno,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount too low")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,

        @Size(max = 140, message = "Note can be at most 140 characters")
        String note) {
}
