package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.RecurringPayment;

import java.math.BigDecimal;
import java.time.Instant;

public record RecurringPaymentResponse(long id, long payeePhno, BigDecimal amount, String note, int intervalDays,
                                       String status, Instant createdAt, Instant nextRunAt, Instant lastRunAt) {

    public static RecurringPaymentResponse from(RecurringPayment r) {
        return new RecurringPaymentResponse(r.getId(), r.getPayeePhno(), r.getAmount(), r.getNote(), r.getIntervalDays(),
                r.getStatus().name(), r.getCreatedAt(), r.getNextRunAt(), r.getLastRunAt());
    }
}
