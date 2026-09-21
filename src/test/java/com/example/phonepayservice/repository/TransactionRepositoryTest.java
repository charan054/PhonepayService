package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// replace = ANY swaps the MySQL datasource for an in-memory H2 database, so these tests can never touch your real schema.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class TransactionRepositoryTest {

    private static final long ASHA = 9876543210L;
    private static final long RAVI = 9123456789L;
    private static final long MEENA = 9000000001L;

    @Autowired
    private TransactionRepository repository;
    @Autowired
    private EntityManager entityManager;

    private Transaction newTransaction(long transactionId, long payer, Long receiver, String amount) {
        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        t.setPhno(payer);
        t.setReceiverPhno(receiver);
        t.setMode(receiver == null ? "Payment" : "Transfer");
        t.setAmount(new BigDecimal(amount));
        t.setStatus(TransactionStatus.COMPLETED);
        t.setCreatedAt(Instant.parse("2026-09-21T10:00:00Z"));
        return t;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ---------- transaction numbers ----------

    @Test
    void findMaxTransactionId_isNullWhenThereAreNoTransactions() {
        assertNull(repository.findMaxTransactionId());
    }

    @Test
    void findMaxTransactionId_isTheHighestNumber_notTheLastRowInserted() {
        repository.save(newTransaction(100005, ASHA, RAVI, "10"));
        repository.save(newTransaction(100002, ASHA, RAVI, "10"));
        repository.save(newTransaction(100003, ASHA, RAVI, "10"));
        flushAndClear();

        assertEquals(100005L, repository.findMaxTransactionId());
    }

    @Test
    void database_rejectsTwoTransactionsWithTheSameNumber() {
        repository.saveAndFlush(newTransaction(100000, ASHA, RAVI, "10"));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(newTransaction(100000, RAVI, ASHA, "20")));
    }

    @Test
    void findByTransactionId_findsIt() {
        repository.save(newTransaction(100000, ASHA, RAVI, "10"));
        flushAndClear();

        assertTrue(repository.findByTransactionId(100000).isPresent());
        assertTrue(repository.findByTransactionId(999).isEmpty());
    }

    // ---------- a person's own history ----------

    @Test
    void history_containsWhatThePersonPaidAndReceived_newestFirst() {
        repository.save(newTransaction(100000, ASHA, RAVI, "10"));    // Asha paid Ravi
        repository.save(newTransaction(100001, RAVI, ASHA, "20"));    // Ravi paid Asha
        repository.save(newTransaction(100002, ASHA, null, "30"));    // Asha paid a bill
        flushAndClear();

        List<Transaction> history = repository.findByPhnoOrReceiverPhnoOrderByIdDesc(ASHA, ASHA);

        assertEquals(List.of(100002L, 100001L, 100000L), history.stream().map(Transaction::getTransactionId).toList());
    }

    @Test
    void history_neverContainsSomeoneElsesTransactions() {
        repository.save(newTransaction(100000, ASHA, RAVI, "10"));
        repository.save(newTransaction(100001, RAVI, MEENA, "20"));   // nothing to do with Asha
        flushAndClear();

        List<Transaction> history = repository.findByPhnoOrReceiverPhnoOrderByIdDesc(ASHA, ASHA);

        assertEquals(List.of(100000L), history.stream().map(Transaction::getTransactionId).toList());
    }

    @Test
    void history_ofSomeoneWithNoTransactions_isEmpty() {
        repository.save(newTransaction(100000, ASHA, RAVI, "10"));
        flushAndClear();

        assertTrue(repository.findByPhnoOrReceiverPhnoOrderByIdDesc(MEENA, MEENA).isEmpty());
    }

    // ---------- what is stored ----------

    @Test
    void amount_isStoredExactly_withoutFloatingPointError() {
        repository.save(newTransaction(100000, ASHA, RAVI, "0.10"));
        repository.save(newTransaction(100001, ASHA, RAVI, "1234567.89"));
        flushAndClear();

        assertEquals(new BigDecimal("0.10"), repository.findByTransactionId(100000).orElseThrow().getAmount());
        assertEquals(new BigDecimal("1234567.89"), repository.findByTransactionId(100001).orElseThrow().getAmount());
    }

    @Test
    void aPaymentWithNoReceiver_isStoredWithANullReceiver() {
        repository.save(newTransaction(100000, ASHA, null, "10"));
        flushAndClear();

        assertNull(repository.findByTransactionId(100000).orElseThrow().getReceiverPhno());
    }

    @Test
    void statusFailureReasonAndTimeAreStored() {
        Transaction t = newTransaction(100000, ASHA, RAVI, "10");
        t.setStatus(TransactionStatus.NEEDS_RECONCILIATION);
        t.setFailureReason("Withdrawal not confirmed by the bank");
        repository.save(t);
        flushAndClear();

        Transaction loaded = repository.findByTransactionId(100000).orElseThrow();
        assertEquals(TransactionStatus.NEEDS_RECONCILIATION, loaded.getStatus());
        assertEquals("Withdrawal not confirmed by the bank", loaded.getFailureReason());
        assertEquals(Instant.parse("2026-09-21T10:00:00Z"), loaded.getCreatedAt());
    }

    @Test
    void save_generatesTheRowIdAutomatically() {
        Transaction saved = repository.save(newTransaction(100000, ASHA, RAVI, "10"));

        assertTrue(saved.getId() > 0);
    }
}
