package com.example.phonepayservice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Where a person's PhonePe money went over the last few months. Only COMPLETED transactions count.
 * netSpent = payments + transfers - refunds, so a refunded purchase is not counted as spending.
 */
public record InsightsResponse(List<Month> months, List<Target> topTransferTargets, BigDecimal averageMonthlyNetSpend) {

    public record Month(String month, BigDecimal payments, BigDecimal transfers, BigDecimal refunds,
                        BigDecimal netSpent, BigDecimal received) {
    }

    // someone the person sent money to, across the whole period
    public record Target(long phno, BigDecimal total, long count) {
    }
}
