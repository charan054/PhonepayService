package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The grouping/ordering queries behind InsightsService, against a real (in-memory) database.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class TransactionInsightsQueriesTest {
    private static final long ASHA = 9876543210L;
    private static final long RAVI = 9123456789L;
    private static final long MEENA = 9000000001L;
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-31T23:59:59Z");

    @Autowired
    private TransactionRepository repository;
    private long nextId = 100000;

    private void save(long payer, Long receiver, String mode, String amount, TransactionStatus status, String when) {
        Transaction t = new Transaction();
        t.setTransactionId(nextId++);
        t.setPhno(payer);
        t.setReceiverPhno(receiver);
        t.setMode(mode);
        t.setAmount(new BigDecimal(amount));
        t.setStatus(status);
        t.setCreatedAt(Instant.parse(when));
        repository.save(t);
    }

    @BeforeEach
    void data() {
        save(ASHA, null, "Payment", "250.00", TransactionStatus.COMPLETED, "2026-10-05T10:00:00Z");
        save(ASHA, null, "Payment", "100.00", TransactionStatus.COMPLETED, "2026-10-20T10:00:00Z");
        save(ASHA, null, "Payment", "999.00", TransactionStatus.FAILED, "2026-10-06T10:00:00Z");     // never moved
        save(ASHA, RAVI, "Transfer", "300.00", TransactionStatus.COMPLETED, "2026-10-07T10:00:00Z");
        save(ASHA, RAVI, "Transfer", "200.00", TransactionStatus.COMPLETED, "2026-10-08T10:00:00Z");
        save(ASHA, MEENA, "Transfer", "800.00", TransactionStatus.COMPLETED, "2026-10-09T10:00:00Z");
        save(ASHA, MEENA, "Transfer", "50.00", TransactionStatus.PENDING, "2026-10-09T11:00:00Z");   // not completed
        save(ASHA, null, "Refund", "100.00", TransactionStatus.COMPLETED, "2026-10-21T10:00:00Z");   // credited to ASHA
        save(ASHA, null, "Payment", "75.00", TransactionStatus.COMPLETED, "2026-09-15T10:00:00Z");   // other month
        save(RAVI, ASHA, "Transfer", "40.00", TransactionStatus.COMPLETED, "2026-10-10T10:00:00Z");  // ASHA received
    }

    @Test
    void paymentsAndTransfersAreSummedSeparatelyAndOnlyWhenCompletedInTheMonth() {
        assertEquals(0, new BigDecimal("350.00").compareTo(repository.sumSentByMode(ASHA, "Payment", FROM, TO)));
        assertEquals(0, new BigDecimal("1300.00").compareTo(repository.sumSentByMode(ASHA, "Transfer", FROM, TO)));
    }

    @Test
    void refundsToThePersonAreSummedOnTheirOwn() {
        assertEquals(0, new BigDecimal("100.00").compareTo(repository.sumRefunded(ASHA, FROM, TO)));
        assertEquals(0, BigDecimal.ZERO.compareTo(repository.sumRefunded(RAVI, FROM, TO)));
    }

    @Test
    void topTargetsAreRankedByTotalSentAndLimitedByThePage() {
        List<Object[]> top = repository.topTransferTargets(ASHA, FROM, TO, PageRequest.of(0, 5));

        assertEquals(2, top.size());   // the PENDING transfer adds nothing, and nobody else was paid
        assertEquals(MEENA, ((Number) top.get(0)[0]).longValue());
        assertEquals(0, new BigDecimal("800.00").compareTo((BigDecimal) top.get(0)[1]));
        assertEquals(RAVI, ((Number) top.get(1)[0]).longValue());
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) top.get(1)[1]));
        assertEquals(2L, ((Number) top.get(1)[2]).longValue());

        assertEquals(1, repository.topTransferTargets(ASHA, FROM, TO, PageRequest.of(0, 1)).size());
    }
}
