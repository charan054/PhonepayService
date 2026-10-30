package com.example.phonepayservice.service;

import com.example.phonepayservice.dto.InsightsResponse;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InsightsServiceTest {
    private static final long VIEWER = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private TransactionRepository transactions;
    private InsightsService service;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionRepository.class);
        service = new InsightsService(transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        when(transactions.sumSentByMode(eq(VIEWER), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumRefunded(eq(VIEWER), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumReceived(eq(VIEWER), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.topTransferTargets(eq(VIEWER), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void defaultsToSixMonthsOldestFirstEndingWithTheCurrentOne() {
        InsightsResponse r = service.insights(VIEWER, null);

        assertEquals(List.of("2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10"),
                r.months().stream().map(InsightsResponse.Month::month).toList());
    }

    @Test
    void netSpendIsPaymentsPlusTransfersMinusRefundsAndReceivedExcludesRefunds() {
        Instant octStart = Instant.parse("2026-10-01T00:00:00Z");
        when(transactions.sumSentByMode(eq(VIEWER), eq("Payment"), eq(octStart), any())).thenReturn(new BigDecimal("350"));
        when(transactions.sumSentByMode(eq(VIEWER), eq("Transfer"), eq(octStart), any())).thenReturn(new BigDecimal("1300"));
        when(transactions.sumRefunded(eq(VIEWER), eq(octStart), any())).thenReturn(new BigDecimal("100"));
        when(transactions.sumReceived(eq(VIEWER), eq(octStart), any())).thenReturn(new BigDecimal("140"));   // 100 refund + 40 from Ravi

        InsightsResponse.Month october = service.insights(VIEWER, 1).months().get(0);

        assertEquals(new BigDecimal("350.00"), october.payments());
        assertEquals(new BigDecimal("1300.00"), october.transfers());
        assertEquals(new BigDecimal("100.00"), october.refunds());
        assertEquals(new BigDecimal("1550.00"), october.netSpent());
        assertEquals(new BigDecimal("40.00"), october.received());
    }

    @Test
    void theAverageIsTheNetSpendOverTheWholeWindowEvenForEmptyMonths() {
        when(transactions.sumSentByMode(eq(VIEWER), eq("Payment"), eq(Instant.parse("2026-10-01T00:00:00Z")), any()))
                .thenReturn(new BigDecimal("300"));

        InsightsResponse r = service.insights(VIEWER, 3);

        assertEquals(new BigDecimal("100.00"), r.averageMonthlyNetSpend());
    }

    @Test
    void topTargetsAreMappedFromTheGroupedRows() {
        when(transactions.topTransferTargets(eq(VIEWER), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{9000000001L, new BigDecimal("800"), 1L}));

        InsightsResponse r = service.insights(VIEWER, 6);

        assertEquals(9000000001L, r.topTransferTargets().get(0).phno());
        assertEquals(new BigDecimal("800.00"), r.topTransferTargets().get(0).total());
        assertEquals(1L, r.topTransferTargets().get(0).count());
    }

    @Test
    void aMonthCountOutsideOneToTwelveIsACallerMistake() {
        assertThrows(InvalidRequestException.class, () -> service.insights(VIEWER, 0));
        assertThrows(InvalidRequestException.class, () -> service.insights(VIEWER, 13));
        verifyNoInteractions(transactions);
    }
}
