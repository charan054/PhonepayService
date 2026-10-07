package com.example.phonepayservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RefundRequest(
        // Optional. Send the SAME key on a retry of the SAME refund (e.g. after a lost response) to avoid
        // refunding twice; a different key, or none, is always treated as a brand new refund attempt.
        @Size(max = 100, message = "Idempotency key can be at most 100 characters")
        String idempotencyKey,
        // Optional. A partial refund of this much; omitted means everything still refundable on the payment (for
        // a payment never refunded before, the full amount - exactly the old behaviour).
        @DecimalMin(value = "0.01", message = "Refund amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "Refund amount can have at most 2 decimal places")
        BigDecimal amount) {
}
