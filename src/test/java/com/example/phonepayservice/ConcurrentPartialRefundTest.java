package com.example.phonepayservice;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.repository.TransactionRepository;
import com.example.phonepayservice.service.PhonepeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The guarantee that replaced the old one-refund-per-payment unique index: concurrent partial refunds of the same
 * payment can never add up to more than it was for. Real service, real transactions, real (in-memory) database
 * with real row locks; only the bank is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ConcurrentPartialRefundTest {

    private static final long ASHA = 9876543210L;
    private static final long PAYMENT_ID = 100000L;

    @Autowired
    private PhonepeService service;
    @Autowired
    private TransactionRepository transactions;
    @MockitoBean
    private BankGateway bank;

    @BeforeEach
    void aCompletedPaymentOf250() {
        transactions.deleteAll();
        Transaction payment = new Transaction();
        payment.setTransactionId(PAYMENT_ID);
        payment.setPhno(ASHA);
        payment.setMode("Payment");
        payment.setAmount(new BigDecimal("250.00"));
        payment.setStatus(TransactionStatus.COMPLETED);
        payment.setCreatedAt(Instant.parse("2026-10-07T10:00:00Z"));
        transactions.saveAndFlush(payment);
    }

    @Test
    void concurrentPartialRefundsNeverExceedThePayment() throws Exception {
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    // Each asks for 150 of the 250 - at most one of them can fit.
                    service.refund(ASHA, PAYMENT_ID, new BigDecimal("150.00"), null);
                    return true;
                } catch (InvalidRequestException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, succeeded);
        assertEquals(0, new BigDecimal("150.00").compareTo(transactions.sumRefundedAmount(PAYMENT_ID)));
        verify(bank, times(1)).deposit(eq(ASHA), any());
    }

    @Test
    void sequentialPartialRefundsCanAddUpToExactlyThePayment() {
        service.refund(ASHA, PAYMENT_ID, new BigDecimal("100.00"), null);
        service.refund(ASHA, PAYMENT_ID, new BigDecimal("150.00"), null);

        assertEquals(0, new BigDecimal("250.00").compareTo(transactions.sumRefundedAmount(PAYMENT_ID)));
        InvalidRequestException ex = org.junit.jupiter.api.Assertions.assertThrows(InvalidRequestException.class,
                () -> service.refund(ASHA, PAYMENT_ID, new BigDecimal("0.01"), null));
        assertEquals("This payment has already been refunded", ex.getMessage());
    }
}
