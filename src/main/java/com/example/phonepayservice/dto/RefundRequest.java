package com.example.phonepayservice.dto;

import jakarta.validation.constraints.Size;

public record RefundRequest(
        // Optional. Send the SAME key on a retry of the SAME refund (e.g. after a lost response) to avoid
        // refunding twice; a different key, or none, is always treated as a brand new refund attempt.
        @Size(max = 100, message = "Idempotency key can be at most 100 characters")
        String idempotencyKey) {
}
