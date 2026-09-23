package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.LoginResponse;
import com.example.phonepayservice.dto.PageResponse;
import com.example.phonepayservice.dto.ProfileResponse;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.BalanceException;
import com.example.phonepayservice.exception.BankConflictException;
import com.example.phonepayservice.exception.BankOutcomeUnknownException;
import com.example.phonepayservice.exception.BankUnavailableException;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.TransactionNotFoundException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.exception.UserNotExistException;
import com.example.phonepayservice.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhonepeServiceTest {

    private static final long PAYER = 9876543210L;
    private static final long RECEIVER = 9123456789L;
    private static final long STRANGER = 9000000001L;
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("250.00");

    @Mock
    private BankGateway bank;
    @Mock
    private SessionService sessions;
    @Mock
    private TransactionRepository transactions;

    private PhonepeService service;
    // the status of each transaction at the moment it was written to the database, in order
    private final List<TransactionStatus> writes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new PhonepeService(bank, sessions, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().doAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            writes.add(t.getStatus());
            return t;
        }).when(transactions).saveAndFlush(any(Transaction.class));
        lenient().doAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            writes.add(t.getStatus());
            return t;
        }).when(transactions).save(any(Transaction.class));
    }

    private BankUser bankUser(String name, double balance) {
        BankUser u = new BankUser();
        u.setName(name);
        u.setAcno(1000000000L);
        u.setBalance(balance);
        return u;
    }

    private Transaction row(long transactionId, long payer, Long receiver, TransactionStatus status) {
        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        t.setPhno(payer);
        t.setReceiverPhno(receiver);
        t.setStatus(status);
        t.setAmount(AMOUNT);
        return t;
    }

    // ============ login ============

    @Test
    void login_startsASessionForThatPhoneNumber() {
        when(bank.findUser(PAYER)).thenReturn(bankUser("KUMAR CHARAN", 500));
        when(sessions.start(PAYER)).thenReturn(new SessionService.IssuedSession("tok", NOW.plusSeconds(1800)));

        LoginResponse response = service.login(PAYER);

        assertEquals("tok", response.token());
        assertEquals(NOW.plusSeconds(1800), response.expiresAt());
        assertEquals(PAYER, response.phno());
        assertEquals("KUMAR CHARAN", response.name());
    }

    @ParameterizedTest
    @ValueSource(longs = {123, 987654321L, 98765432101L, 5876543210L})
    void login_invalidPhoneNumber_isRejectedBeforeTheBankIsAsked(long badPhone) {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class, () -> service.login(badPhone));

        assertEquals("Invalid mobile number", ex.getMessage());
        verifyNoInteractions(bank, sessions);
    }

    @Test
    void login_unknownUser_startsNoSession() {
        when(bank.findUser(PAYER)).thenThrow(new UserNotExistException("User not found"));

        assertThrows(UserNotExistException.class, () -> service.login(PAYER));

        verifyNoInteractions(sessions);
    }

    @Test
    void logout_endsTheSession() {
        service.logout("tok");

        verify(sessions).end("tok");
    }

    // ============ account ============

    @Test
    void profile_showsTheBanksNameAccountAndBalance() {
        when(bank.findUser(PAYER)).thenReturn(bankUser("KUMAR CHARAN", 1234.5));

        ProfileResponse profile = service.profile(PAYER);

        assertEquals(PAYER, profile.phno());
        assertEquals("KUMAR CHARAN", profile.name());
        assertEquals(1000000000L, profile.acno());
        assertEquals(new BigDecimal("1234.50"), profile.balance());
    }

    @Test
    void balance_isReportedWithTwoDecimals() {
        when(bank.findUser(PAYER)).thenReturn(bankUser("X", 0.1 + 0.2));   // floating point gives 0.30000000000000004

        BalanceResponse balance = service.balance(PAYER);

        assertEquals(new BigDecimal("0.30"), balance.balance());
    }

    // ============ sendMoney: the happy path ============

    @Test
    void sendMoney_movesTheMoneyInOneAtomicCall_andRecordsIt() {
        when(transactions.findMaxTransactionId()).thenReturn(null);

        Transaction t = service.sendMoney(PAYER, RECEIVER, new BigDecimal("250"));

        InOrder order = inOrder(bank, transactions);
        order.verify(transactions).saveAndFlush(any(Transaction.class));                // 1. write it down as PENDING
        order.verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");          // 2. move it, in one call
        order.verify(transactions).save(any(Transaction.class));                        // 3. write down that it completed
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.COMPLETED), writes);
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        assertEquals("Transfer", t.getMode());
        assertEquals(PAYER, t.getPhno());
        assertEquals(RECEIVER, t.getReceiverPhno());
        assertEquals(AMOUNT, t.getAmount());
        assertEquals(NOW, t.getCreatedAt());
    }

    @Test
    void sendMoney_firstTransactionGets100000_thenOneMoreThanTheHighest() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        assertEquals(100000L, service.sendMoney(PAYER, RECEIVER, AMOUNT).getTransactionId());
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");

        when(transactions.findMaxTransactionId()).thenReturn(100007L);
        assertEquals(100008L, service.sendMoney(PAYER, RECEIVER, AMOUNT).getTransactionId());
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100008");
    }

    // The idempotency key is derived only from OUR OWN transaction id, chosen before the bank is ever called, so
    // there is no separate lookup step to skip - the atomic transfer endpoint itself is where "does the receiver
    // exist" gets answered, and it answers it having moved nothing if the answer is no.
    @Test
    void sendMoney_toAnUnknownNumber_neverTakesTheMoney() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new UserNotExistException("User not found")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        assertThrows(UserNotExistException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void sendMoney_toYourself_isRejected() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class, () -> service.sendMoney(PAYER, PAYER, AMOUNT));

        assertEquals("You cannot send money to yourself", ex.getMessage());
        verifyNoInteractions(bank, transactions);
    }

    @ParameterizedTest
    @ValueSource(longs = {123, 5876543210L, 98765432101L})
    void sendMoney_invalidReceiverNumber_isRejected(long badReceiver) {
        assertThrows(InvalidRequestException.class, () -> service.sendMoney(PAYER, badReceiver, AMOUNT));

        verifyNoInteractions(bank, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01", "0.00"})
    void sendMoney_zeroOrNegativeAmount_isRejected(String amount) {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.sendMoney(PAYER, RECEIVER, new BigDecimal(amount)));

        assertEquals("Amount too low", ex.getMessage());
        verifyNoInteractions(bank, transactions);
    }

    @Test
    void sendMoney_moreThanTwoDecimals_isRejected() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.sendMoney(PAYER, RECEIVER, new BigDecimal("1.234")));

        assertEquals("Amount can have at most 2 decimal places", ex.getMessage());
        verifyNoInteractions(bank, transactions);
    }

    @Test
    void sendMoney_missingAmount_isRejected() {
        assertThrows(InvalidRequestException.class, () -> service.sendMoney(PAYER, RECEIVER, null));

        verifyNoInteractions(bank, transactions);
    }

    // ============ sendMoney: the bank refuses the transfer outright ============

    @Test
    void sendMoney_insufficientFunds_failsCleanly() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BalanceException("Insufficient Funds")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        BalanceException ex = assertThrows(BalanceException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertEquals("Insufficient Funds", ex.getMessage());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void sendMoney_bankUnreachable_failsCleanly() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankUnavailableException("down", null)).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        assertThrows(BankUnavailableException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    // ============ sendMoney: the outcome is ambiguous (timeout) - retried with the SAME idempotency key ============

    @Test
    void sendMoney_transferOutcomeUnknownOnce_retriesWithTheSameKey_andSucceeds() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankOutcomeUnknownException("timeout", null)).doNothing()
                .when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT);

        verify(bank, times(2)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");   // same key both times
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void sendMoney_transferStaysUnknown_needsAPersonAfterMaxAttempts() {
        // A persistent timeout: the bank may or may not have completed it. Guessing either way could create or
        // destroy money, so this is the one outcome that must NOT be resolved automatically.
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertTrue(ex.getMessage().contains("could not confirm"), ex.getMessage());
        assertTrue(ex.getMessage().contains("100000"), "the user needs the reference number: " + ex.getMessage());
        verify(bank, times(3)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
    }

    // ============ sendMoney: the bank was busy (a conflict) - this one is definite, not ambiguous ============

    @Test
    void sendMoney_transferConflictTwice_isRetriedAndSucceeds() {
        // A conflict means the bank's own transaction rolled back completely - nothing committed - so retrying
        // with the same key can never move the money twice.
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankConflictException("busy")).doThrow(new BankConflictException("busy")).doNothing()
                .when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT);

        verify(bank, times(3)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void sendMoney_transferStaysBusy_failsCleanly_notNeedsReconciliation() {
        // Unlike a timeout, a conflict is never ambiguous: the bank guarantees nothing committed, so giving up
        // after a conflict is a plain failure (nothing moved), not a case that needs a human to check.
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankConflictException("busy")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertEquals("The bank was too busy to complete your transfer. Please try again.", ex.getMessage());
        verify(bank, times(3)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    // ============ bookkeeping ============

    @Test
    void ifThePaymentCannotBeWrittenDown_noMoneyMoves() {
        doThrow(new DataIntegrityViolationException("dup")).when(transactions).saveAndFlush(any(Transaction.class));
        when(transactions.findMaxTransactionId()).thenReturn(100000L);

        assertThrows(DataIntegrityViolationException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        verify(transactions, times(5)).saveAndFlush(any(Transaction.class));   // gave up after 5 attempts
        verify(bank, never()).transfer(any(Long.class), any(Long.class), any(), any());
    }

    @Test
    void transactionIdCollision_retriesWithTheNextFreeNumber_andThatBecomesTheIdempotencyKeyToo() {
        // another request grabbed 100006 a moment ago; the unique constraint refused ours
        doThrow(new DataIntegrityViolationException("dup"))
                .doAnswer(inv -> inv.getArgument(0))
                .when(transactions).saveAndFlush(any(Transaction.class));
        when(transactions.findMaxTransactionId()).thenReturn(100005L, 100006L);

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT);

        assertEquals(100007L, t.getTransactionId());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100007");
    }

    @Test
    void ifTheFinalStatusCannotBeSaved_thePaymentStillCountsAsDone() {
        // the money HAS moved; failing the request now would only hide that
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new RuntimeException("db down")).when(transactions).save(any(Transaction.class));

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT);

        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
    }

    // ============ makePayment ============

    @Test
    void makePayment_takesTheMoneyAndRecordsAPaymentWithNoReceiver() {
        Transaction t = service.makePayment(PAYER, new BigDecimal("99.5"));

        verify(bank).withdraw(PAYER, new BigDecimal("99.50"));
        verify(bank, never()).deposit(any(Long.class), any());
        assertEquals("Payment", t.getMode());
        assertNull(t.getReceiverPhno());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.COMPLETED), writes);
    }

    @Test
    void makePayment_insufficientFunds_isRecordedAsFailed() {
        doThrow(new BalanceException("Insufficient Funds")).when(bank).withdraw(eq(PAYER), any());

        assertThrows(BalanceException.class, () -> service.makePayment(PAYER, AMOUNT));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void makePayment_outcomeUnknown_needsAPerson() {
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).withdraw(eq(PAYER), any());

        assertThrows(TransferFailedException.class, () -> service.makePayment(PAYER, AMOUNT));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "2.345"})
    void makePayment_badAmount_isRejected(String amount) {
        assertThrows(InvalidRequestException.class, () -> service.makePayment(PAYER, new BigDecimal(amount)));

        verifyNoInteractions(bank, transactions);
    }

    // ============ history: people only ever see their own money ============

    // The visibility rule itself (a receiver never sees an unfinished payment) now lives in the repository query
    // (TransactionRepository.findVisibleTo) and is tested there; this service just wires page/size into a Pageable.
    @Test
    void transactionsOf_asksTheRepositoryForANewestFirstPage() {
        when(transactions.findVisibleTo(eq(RECEIVER), any())).thenReturn(new PageImpl<>(List.of(
                row(1, PAYER, RECEIVER, TransactionStatus.COMPLETED))));

        PageResponse<Transaction> result = service.transactionsOf(RECEIVER, 0, 20);

        assertEquals(List.of(1L), result.content().stream().map(Transaction::getTransactionId).toList());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).findVisibleTo(eq(RECEIVER), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(Sort.by("id").descending(), pageable.getValue().getSort());
    }

    @Test
    void transactionsOf_negativePage_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> service.transactionsOf(RECEIVER, -1, 20));

        verifyNoInteractions(transactions);
    }

    @Test
    void transactionsOf_sizeOutOfRange_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> service.transactionsOf(RECEIVER, 0, 0));
        assertThrows(InvalidRequestException.class, () -> service.transactionsOf(RECEIVER, 0, PhonepeService.MAX_PAGE_SIZE + 1));

        verifyNoInteractions(transactions);
    }

    @Test
    void transaction_thePayerCanSeeIt_evenWhileItIsNotCompleted() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(row(100000, PAYER, RECEIVER, TransactionStatus.PENDING)));

        assertEquals(100000, service.transaction(PAYER, 100000).getTransactionId());
    }

    @Test
    void transaction_theReceiverCanSeeItOnceCompleted() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(row(100000, PAYER, RECEIVER, TransactionStatus.COMPLETED)));

        assertEquals(100000, service.transaction(RECEIVER, 100000).getTransactionId());
    }

    @Test
    void transaction_theReceiverCannotSeeItWhileItIsNotCompleted() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(row(100000, PAYER, RECEIVER, TransactionStatus.FAILED)));

        assertThrows(TransactionNotFoundException.class, () -> service.transaction(RECEIVER, 100000));
    }

    @Test
    void transaction_aStrangerGetsNotFound_notForbidden() {
        // "not found" (rather than "forbidden") avoids confirming that someone else's transaction exists
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(row(100000, PAYER, RECEIVER, TransactionStatus.COMPLETED)));

        assertThrows(TransactionNotFoundException.class, () -> service.transaction(STRANGER, 100000));
    }

    @Test
    void transaction_unknownNumber_isNotFound() {
        when(transactions.findByTransactionId(555)).thenReturn(Optional.empty());

        TransactionNotFoundException ex = assertThrows(TransactionNotFoundException.class, () -> service.transaction(PAYER, 555));

        assertEquals("Transaction not found", ex.getMessage());
    }
}
