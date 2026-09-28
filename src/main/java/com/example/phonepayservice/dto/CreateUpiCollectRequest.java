package com.example.phonepayservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// Created by a trusted backend (see UpiCollectController), never directly by a browser - merchantReference is
// that backend's own id for what this payment is for (e.g. "OrderService-42"), unique so a retried call is
// idempotent instead of raising a duplicate request.
public record CreateUpiCollectRequest(
        @NotBlank(message = "Merchant reference is required")
        @Size(max = 100, message = "Merchant reference can be at most 100 characters")
        String merchantReference,

        @NotBlank(message = "UPI ID is required")
        String upiId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount too low")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,

        @Size(max = 140, message = "Note can be at most 140 characters")
        String note) {
}
