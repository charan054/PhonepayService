package com.example.phonepayservice.dto;

import java.math.BigDecimal;

/** How much a person sent and received in one calendar month. Only ever counts COMPLETED transactions. */
public record MonthlySummaryResponse(String month, BigDecimal totalSent, long sentCount,
                                     BigDecimal totalReceived, long receivedCount) {
}
