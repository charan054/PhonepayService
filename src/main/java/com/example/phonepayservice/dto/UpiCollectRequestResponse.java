package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.UpiCollectRequest;
import com.example.phonepayservice.util.UpiId;

import java.math.BigDecimal;
import java.time.Instant;

public record UpiCollectRequestResponse(long id, String merchantReference, long payerPhno, String payerUpiId,
                                         BigDecimal amount, String note, String status, Instant createdAt,
                                         Instant expiresAt, Instant resolvedAt, Long resultTransactionId) {

    public static UpiCollectRequestResponse from(UpiCollectRequest r) {
        return new UpiCollectRequestResponse(r.getId(), r.getMerchantReference(), r.getPayerPhno(),
                UpiId.forPhno(r.getPayerPhno()), r.getAmount(), r.getNote(), r.getStatus().name(),
                r.getCreatedAt(), r.getExpiresAt(), r.getResolvedAt(), r.getResultTransactionId());
    }
}
