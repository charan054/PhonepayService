package com.example.phonepayservice.service;

import com.example.phonepayservice.dto.InsightsResponse;
import com.example.phonepayservice.dto.InsightsResponse.Month;
import com.example.phonepayservice.dto.InsightsResponse.Target;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Month-by-month spending for one person, built from the same transaction history as the monthly summary (calendar
 * months in UTC, completed transactions only) but split into shop payments, transfers to people and refunds.
 */
@Service
public class InsightsService {
    static final int DEFAULT_MONTHS = 6;
    static final int MAX_MONTHS = 12;
    static final int TOP_TARGETS = 3;

    private final TransactionRepository transactions;
    private final Clock clock;

    public InsightsService(TransactionRepository transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    /** months = how many calendar months to show, ending with the current one; null means the default of 6. */
    public InsightsResponse insights(long viewer, Integer months) {
        int count = months == null ? DEFAULT_MONTHS : months;
        if (count < 1 || count > MAX_MONTHS) {
            throw new InvalidRequestException("months must be between 1 and " + MAX_MONTHS + ".");
        }
        YearMonth current = YearMonth.now(clock);
        List<Month> rows = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        for (int i = count - 1; i >= 0; i--) {
            YearMonth ym = current.minusMonths(i);
            Instant from = start(ym);
            Instant to = end(ym);
            BigDecimal payments = money(transactions.sumSentByMode(viewer, "Payment", from, to));
            BigDecimal transfers = money(transactions.sumSentByMode(viewer, "Transfer", from, to));
            BigDecimal refunds = money(transactions.sumRefunded(viewer, from, to));
            BigDecimal net = payments.add(transfers).subtract(refunds);
            // sumReceived counts refunds as money in; they are shown on their own, so take them out of "received"
            BigDecimal received = money(transactions.sumReceived(viewer, from, to)).subtract(refunds);
            rows.add(new Month(ym.toString(), payments, transfers, refunds, net, received));
            netTotal = netTotal.add(net);
        }

        List<Target> targets = transactions.topTransferTargets(viewer, start(current.minusMonths(count - 1L)), end(current),
                        PageRequest.of(0, TOP_TARGETS)).stream()
                .map(r -> new Target(((Number) r[0]).longValue(), money((BigDecimal) r[1]), ((Number) r[2]).longValue()))
                .toList();
        return new InsightsResponse(rows, targets, netTotal.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
    }

    private static Instant start(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant end(YearMonth ym) {
        return ym.atEndOfMonth().atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
