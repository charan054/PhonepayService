package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.PaymentDispute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class DisputeDtos {
    private DisputeDtos() {
    }

    public record RaiseRequest(long transactionId, @NotBlank @Size(max = 500) String reason) {
    }

    // approve = true pays the payment back; note is the explanation the customer will see (optional)
    public record ResolveRequest(boolean approve, @Size(max = 500) String note) {
    }

    public record DisputeResponse(long id, long transactionId, long phno, String reason, String status,
                                  String resolutionNote, Long refundTransactionId, Instant createdAt, Instant resolvedAt) {
        public static DisputeResponse from(PaymentDispute d) {
            return new DisputeResponse(d.getId(), d.getTransactionId(), d.getPhno(), d.getReason(), d.getStatus(),
                    d.getResolutionNote(), d.getRefundTransactionId(), d.getCreatedAt(), d.getResolvedAt());
        }
    }
}
