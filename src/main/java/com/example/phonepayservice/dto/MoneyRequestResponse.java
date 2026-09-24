package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.MoneyRequest;

import java.math.BigDecimal;
import java.time.Instant;

// "direction" mirrors TransactionResponse's own field: OUTGOING means the viewer asked for the money,
// INCOMING means someone is asking the viewer for it.
public record MoneyRequestResponse(long id, long requesterPhno, long payerPhno, BigDecimal amount, String note,
                                   String status, String direction, Instant createdAt, Instant resolvedAt,
                                   Long resultingTransactionId) {

    public static MoneyRequestResponse from(MoneyRequest r, long viewer) {
        return new MoneyRequestResponse(r.getId(), r.getRequesterPhno(), r.getPayerPhno(), r.getAmount(), r.getNote(),
                r.getStatus().name(), r.getRequesterPhno() == viewer ? "OUTGOING" : "INCOMING",
                r.getCreatedAt(), r.getResolvedAt(), r.getResultingTransactionId());
    }
}
