package com.example.phonepayservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount too low")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,

        @Size(max = 140, message = "Note can be at most 140 characters")
        String note,

        // Optional. Send the SAME key on a retry of the SAME request (e.g. after a lost response) to avoid
        // paying twice; a different key, or none, is always treated as a brand new payment.
        @Size(max = 100, message = "Idempotency key can be at most 100 characters")
        String idempotencyKey) {
}
