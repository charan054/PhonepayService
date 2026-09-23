package com.example.phonepayservice.repository;

import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by("id").descending());
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

        Page<Transaction> history = repository.findVisibleTo(ASHA, null, null, newestFirst(0, 20));

        assertEquals(List.of(100002L, 100001L, 100000L), history.getContent().stream().map(Transaction::getTransactionId).toList());
    }

    @Test
    void history_neverContainsSomeoneElsesTransactions() {
        repository.save(newTransaction(100000, ASHA, RAVI, "10"));
        repository.save(newTransaction(100001, RAVI, MEENA, "20"));   // nothing to do with Asha
        flushAndClear();

        Page<Transaction> history = repository.findVisibleTo(ASHA, null, null, newestFirst(0, 20));

        assertEquals(List.of(100000L), history.getContent().stream().map(Transaction::getTransactionId).toList());
    }

    @Test
    void history_ofSomeoneWithNoTransactions_isEmpty() {
        repository.save(newTransaction(100000, ASHA, RAVI, "10"));
        flushAndClear();

        assertTrue(repository.findVisibleTo(MEENA, null, null, newestFirst(0, 20)).isEmpty());
    }

    @Test
    void history_hidesAnUnfinishedPaymentFromItsReceiver_butNotFromItsPayer() {
        Transaction pending = newTransaction(100000, ASHA, RAVI, "10");
        pending.setStatus(TransactionStatus.NEEDS_RECONCILIATION);
        repository.save(pending);
        flushAndClear();

        assertEquals(List.of(100000L), repository.findVisibleTo(ASHA, null, null, newestFirst(0, 20))
                .getContent().stream().map(Transaction::getTransactionId).toList());
        assertTrue(repository.findVisibleTo(RAVI, null, null, newestFirst(0, 20)).isEmpty(), "the money may never have reached Ravi");
    }

    @Test
    void history_isPaginated() {
        for (long id = 100000; id < 100005; id++) {
            repository.save(newTransaction(id, ASHA, RAVI, "10"));
        }
        flushAndClear();

        Page<Transaction> firstPage = repository.findVisibleTo(ASHA, null, null, newestFirst(0, 2));
        Page<Transaction> secondPage = repository.findVisibleTo(ASHA, null, null, newestFirst(1, 2));

        assertEquals(5, firstPage.getTotalElements());
        assertEquals(3, firstPage.getTotalPages());
        assertEquals(List.of(100004L, 100003L), firstPage.getContent().stream().map(Transaction::getTransactionId).toList());
        assertEquals(List.of(100002L, 100001L), secondPage.getContent().stream().map(Transaction::getTransactionId).toList());
    }

    // ---------- optional date-range filter ----------

    @Test
    void findVisibleTo_filtersByCreatedAt_whenFromAndToAreGiven() {
        Transaction early = newTransaction(100000, ASHA, RAVI, "10");
        early.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        Transaction inRange = newTransaction(100001, ASHA, RAVI, "10");
        inRange.setCreatedAt(Instant.parse("2026-01-15T00:00:00Z"));
        Transaction late = newTransaction(100002, ASHA, RAVI, "10");
        late.setCreatedAt(Instant.parse("2026-02-01T00:00:00Z"));
        repository.save(early);
        repository.save(inRange);
        repository.save(late);
        flushAndClear();

        Page<Transaction> filtered = repository.findVisibleTo(ASHA,
                Instant.parse("2026-01-10T00:00:00Z"), Instant.parse("2026-01-20T00:00:00Z"), newestFirst(0, 20));

        assertEquals(List.of(100001L), filtered.getContent().stream().map(Transaction::getTransactionId).toList());
    }

    @Test
    void findVisibleTo_fromAndToAreInclusive() {
        Transaction t = newTransaction(100000, ASHA, RAVI, "10");
        Instant exact = Instant.parse("2026-01-15T00:00:00Z");
        t.setCreatedAt(exact);
        repository.save(t);
        flushAndClear();

        assertEquals(1, repository.findVisibleTo(ASHA, exact, exact, newestFirst(0, 20)).getTotalElements());
    }

    @Test
    void findVisibleTo_noDateRange_returnsEverything() {
        Transaction t = newTransaction(100000, ASHA, RAVI, "10");
        t.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));   // long before "now"
        repository.save(t);
        flushAndClear();

        assertEquals(1, repository.findVisibleTo(ASHA, null, null, newestFirst(0, 20)).getTotalElements());
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
