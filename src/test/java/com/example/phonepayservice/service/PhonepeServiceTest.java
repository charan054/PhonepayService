package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.BankLoginResult;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.LoginResponse;
import com.example.phonepayservice.dto.MonthlySummaryResponse;
import com.example.phonepayservice.dto.PageResponse;
import com.example.phonepayservice.dto.ProfileResponse;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.AccountLockedException;
import com.example.phonepayservice.exception.BalanceException;
import com.example.phonepayservice.exception.BankConflictException;
import com.example.phonepayservice.exception.BankOutcomeUnknownException;
import com.example.phonepayservice.exception.BankUnavailableException;
import com.example.phonepayservice.exception.InvalidCredentialsException;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.TransactionNotFoundException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.exception.UserNotExistException;
import com.example.phonepayservice.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
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
import static org.mockito.Mockito.mock;
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
        // A mock transaction manager: TransactionTemplate just runs the refund-reservation callback inline.
        service = new PhonepeService(bank, sessions, transactions, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));
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

    private BankUser bankUser(String name, BigDecimal balance) {
        BankUser u = new BankUser();
        u.setName(name);
        u.setAcno(1000000000L);
        u.setBalance(balance);
        return u;
    }

    private BankLoginResult bankLoginResult(String name) {
        BankLoginResult r = new BankLoginResult();
        r.setName(name);
        return r;
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
        when(bank.login(PAYER, "1234")).thenReturn(bankLoginResult("KUMAR CHARAN"));
        when(sessions.start(PAYER)).thenReturn(new SessionService.IssuedSession("tok", NOW.plusSeconds(1800)));

        LoginResponse response = service.login(PAYER, "1234");

        assertEquals("tok", response.token());
        assertEquals(NOW.plusSeconds(1800), response.expiresAt());
        assertEquals(PAYER, response.phno());
        assertEquals("KUMAR CHARAN", response.name());
    }

    @ParameterizedTest
    @ValueSource(longs = {123, 987654321L, 98765432101L, 5876543210L})
    void login_invalidPhoneNumber_isRejectedBeforeTheBankIsAsked(long badPhone) {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class, () -> service.login(badPhone, "1234"));

        assertEquals("Invalid mobile number", ex.getMessage());
        verifyNoInteractions(bank, sessions);
    }

    // The bank keeps this generic on purpose (wrong PIN and "no such phone" look identical), so PhonepayService
    // must not narrow it either - narrowing it would let a caller tell the two apart.
    @Test
    void login_wrongPinOrUnknownUser_startsNoSession() {
        when(bank.login(PAYER, "0000")).thenThrow(new InvalidCredentialsException("Invalid phone number or PIN"));

        assertThrows(InvalidCredentialsException.class, () -> service.login(PAYER, "0000"));

        verifyNoInteractions(sessions);
    }

    @Test
    void login_accountLocked_startsNoSession() {
        when(bank.login(PAYER, "1234")).thenThrow(new AccountLockedException("Too many failed attempts. Try again after 2026-09-23T10:15:00Z."));

        assertThrows(AccountLockedException.class, () -> service.login(PAYER, "1234"));

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
        when(bank.findUser(PAYER)).thenReturn(bankUser("KUMAR CHARAN", new BigDecimal("1234.5")));

        ProfileResponse profile = service.profile(PAYER);

        assertEquals(PAYER, profile.phno());
        assertEquals("KUMAR CHARAN", profile.name());
        assertEquals(1000000000L, profile.acno());
        assertEquals(new BigDecimal("1234.50"), profile.balance());
    }

    @Test
    void balance_isReportedWithTwoDecimals() {
        when(bank.findUser(PAYER)).thenReturn(bankUser("X", new BigDecimal("0.3")));

        BalanceResponse balance = service.balance(PAYER);

        assertEquals(new BigDecimal("0.30"), balance.balance());
    }

    // ============ sendMoney: the happy path ============

    @Test
    void sendMoney_movesTheMoneyInOneAtomicCall_andRecordsIt() {
        when(transactions.findMaxTransactionId()).thenReturn(null);

        Transaction t = service.sendMoney(PAYER, RECEIVER, new BigDecimal("250"), null, null);

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
    void sendMoney_storesTheNote() {
        when(transactions.findMaxTransactionId()).thenReturn(null);

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT, "rent", null);

        assertEquals("rent", t.getNote());
    }

    @Test
    void sendMoney_blankNote_isStoredAsNull() {
        when(transactions.findMaxTransactionId()).thenReturn(null);

        assertEquals(null, service.sendMoney(PAYER, RECEIVER, AMOUNT, "", null).getNote());
        assertEquals(null, service.sendMoney(PAYER, RECEIVER, AMOUNT, "   ", null).getNote());
        assertEquals(null, service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null).getNote());
    }

    @Test
    void sendMoney_noteIsTrimmed() {
        when(transactions.findMaxTransactionId()).thenReturn(null);

        assertEquals("rent", service.sendMoney(PAYER, RECEIVER, AMOUNT, "  rent  ", null).getNote());
    }

    @Test
    void sendMoney_firstTransactionGets100000_thenOneMoreThanTheHighest() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        assertEquals(100000L, service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null).getTransactionId());
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");

        when(transactions.findMaxTransactionId()).thenReturn(100007L);
        assertEquals(100008L, service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null).getTransactionId());
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100008");
    }

    // The idempotency key is derived only from OUR OWN transaction id, chosen before the bank is ever called, so
    // there is no separate lookup step to skip - the atomic transfer endpoint itself is where "does the receiver
    // exist" gets answered, and it answers it having moved nothing if the answer is no.
    @Test
    void sendMoney_toAnUnknownNumber_neverTakesTheMoney() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new UserNotExistException("User not found")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        assertThrows(UserNotExistException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void sendMoney_toYourself_isRejected() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class, () -> service.sendMoney(PAYER, PAYER, AMOUNT, null, null));

        assertEquals("You cannot send money to yourself", ex.getMessage());
        verifyNoInteractions(bank, transactions);
    }

    @ParameterizedTest
    @ValueSource(longs = {123, 5876543210L, 98765432101L})
    void sendMoney_invalidReceiverNumber_isRejected(long badReceiver) {
        assertThrows(InvalidRequestException.class, () -> service.sendMoney(PAYER, badReceiver, AMOUNT, null, null));

        verifyNoInteractions(bank, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01", "0.00"})
    void sendMoney_zeroOrNegativeAmount_isRejected(String amount) {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.sendMoney(PAYER, RECEIVER, new BigDecimal(amount), null, null));

        assertEquals("Amount too low", ex.getMessage());
        verifyNoInteractions(bank, transactions);
    }

    @Test
    void sendMoney_moreThanTwoDecimals_isRejected() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.sendMoney(PAYER, RECEIVER, new BigDecimal("1.234"), null, null));

        assertEquals("Amount can have at most 2 decimal places", ex.getMessage());
        verifyNoInteractions(bank, transactions);
    }

    @Test
    void sendMoney_missingAmount_isRejected() {
        assertThrows(InvalidRequestException.class, () -> service.sendMoney(PAYER, RECEIVER, null, null, null));

        verifyNoInteractions(bank, transactions);
    }

    // ============ sendMoney: the bank refuses the transfer outright ============

    @Test
    void sendMoney_insufficientFunds_failsCleanly() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BalanceException("Insufficient Funds")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        BalanceException ex = assertThrows(BalanceException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertEquals("Insufficient Funds", ex.getMessage());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void sendMoney_bankUnreachable_failsCleanly() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankUnavailableException("down", null)).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        assertThrows(BankUnavailableException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    // ============ sendMoney: the outcome is ambiguous (timeout) - retried with the SAME idempotency key ============

    @Test
    void sendMoney_transferOutcomeUnknownOnce_retriesWithTheSameKey_andSucceeds() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankOutcomeUnknownException("timeout", null)).doNothing()
                .when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null);

        verify(bank, times(2)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");   // same key both times
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void sendMoney_transferStaysUnknown_needsAPersonAfterMaxAttempts() {
        // A persistent timeout: the bank may or may not have completed it. Guessing either way could create or
        // destroy money, so this is the one outcome that must NOT be resolved automatically.
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertTrue(ex.getMessage().contains("could not confirm"), ex.getMessage());
        assertTrue(ex.getMessage().contains("100000"), "the user needs the reference number: " + ex.getMessage());
        verify(bank, times(3)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
    }

    // The worst case this app can hit: the bank's outcome is already unknown, AND the database won't even take
    // the NEEDS_RECONCILIATION flag meant to make a human look at it. That flag must never just get silently
    // dropped - the caller still has to be told, loudly, that something needs manual attention.
    @Test
    void sendMoney_transferStaysUnknown_andTheReconciliationFlagCannotBeSavedEither_stillTellsTheCaller() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());
        doThrow(new RuntimeException("db down")).when(transactions).save(any(Transaction.class));

        TransferFailedException ex = assertThrows(TransferFailedException.class,
                () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertTrue(ex.getMessage().contains("check your balance") || ex.getMessage().contains("Check your balance"), ex.getMessage());
        verify(transactions, times(3)).save(any(Transaction.class));   // gave up only after retrying
    }

    // ============ sendMoney: the bank was busy (a conflict) - this one is definite, not ambiguous ============

    @Test
    void sendMoney_transferConflictTwice_isRetriedAndSucceeds() {
        // A conflict means the bank's own transaction rolled back completely - nothing committed - so retrying
        // with the same key can never move the money twice.
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankConflictException("busy")).doThrow(new BankConflictException("busy")).doNothing()
                .when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null);

        verify(bank, times(3)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void sendMoney_transferStaysBusy_failsCleanly_notNeedsReconciliation() {
        // Unlike a timeout, a conflict is never ambiguous: the bank guarantees nothing committed, so giving up
        // after a conflict is a plain failure (nothing moved), not a case that needs a human to check.
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankConflictException("busy")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertEquals("The bank was too busy to complete your transfer. Please try again.", ex.getMessage());
        verify(bank, times(3)).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    // A retry that fires immediately, back-to-back, hits an already-struggling bank at the worst possible
    // moment. There must be a real pause between attempts - checked as a lower bound on wall-clock time (never
    // an upper bound, which would be flaky on a slow machine) rather than by mocking Thread.sleep.
    @Test
    void sendMoney_conflictRetries_pauseBetweenAttempts() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new BankConflictException("busy")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());

        long start = System.nanoTime();
        assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 2 backoffs happen before the 3rd (final) attempt gives up; each is at least 120ms (see
        // PhonepeService.BACKOFF_BASE_MS), so well under half of that floor as a generous lower bound.
        assertTrue(elapsedMs >= 200, "expected a real pause between retries, only took " + elapsedMs + "ms");
    }

    // ============ bookkeeping ============

    @Test
    void ifThePaymentCannotBeWrittenDown_noMoneyMoves() {
        doThrow(new DataIntegrityViolationException("dup")).when(transactions).saveAndFlush(any(Transaction.class));
        when(transactions.findMaxTransactionId()).thenReturn(100000L);

        assertThrows(DataIntegrityViolationException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

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

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null);

        assertEquals(100007L, t.getTransactionId());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100007");
    }

    // ============ sendMoney: idempotency key ============

    @Test
    void sendMoney_sameIdempotencyKeyAndSameRequest_returnsTheExistingTransaction_withoutCallingTheBankAgain() {
        Transaction existing = new Transaction();
        existing.setTransactionId(100000);
        existing.setPhno(PAYER);
        existing.setReceiverPhno(RECEIVER);
        existing.setAmount(AMOUNT);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "key-1")).thenReturn(Optional.of(existing));

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT, null, "key-1");

        assertEquals(existing, t);
        verifyNoInteractions(bank);
        verify(transactions, never()).saveAndFlush(any());
    }

    @Test
    void sendMoney_sameIdempotencyKey_differentReceiver_throwsInvalidRequest_andNeverCallsTheBank() {
        Transaction existing = new Transaction();
        existing.setPhno(PAYER);
        existing.setReceiverPhno(STRANGER);
        existing.setAmount(AMOUNT);
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "key-1")).thenReturn(Optional.of(existing));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, "key-1"));

        assertEquals("This idempotency key was already used for a different request.", ex.getMessage());
        verifyNoInteractions(bank);
    }

    @Test
    void sendMoney_sameIdempotencyKey_differentAmount_throwsInvalidRequest() {
        Transaction existing = new Transaction();
        existing.setPhno(PAYER);
        existing.setReceiverPhno(RECEIVER);
        existing.setAmount(new BigDecimal("999.00"));
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "key-1")).thenReturn(Optional.of(existing));

        assertThrows(InvalidRequestException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, "key-1"));
        verifyNoInteractions(bank);
    }

    // A concurrent, near-simultaneous duplicate request can lose to the winner's insert at the database's unique
    // constraint rather than at the earlier "does it already exist" lookup. Either way the money must move once.
    @Test
    void sendMoney_losesAConcurrentRaceOnTheIdempotencyKey_returnsTheWinnersTransaction_withoutCallingTheBank() {
        Transaction winner = new Transaction();
        winner.setTransactionId(100000);
        winner.setPhno(PAYER);
        winner.setReceiverPhno(RECEIVER);
        winner.setAmount(AMOUNT);
        winner.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findMaxTransactionId()).thenReturn(null);
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "key-1"))
                .thenReturn(Optional.empty())      // nothing yet at the fast pre-check...
                .thenReturn(Optional.of(winner));  // ...but the winner's row exists by the time we look again
        doThrow(new DataIntegrityViolationException("dup")).when(transactions).saveAndFlush(any(Transaction.class));

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT, null, "key-1");

        assertEquals(winner, t);
        verifyNoInteractions(bank);
    }

    @Test
    void sendMoney_withoutAnIdempotencyKey_neverConsultsThatLookup() {
        when(transactions.findMaxTransactionId()).thenReturn(null);

        service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null);
        service.sendMoney(PAYER, RECEIVER, AMOUNT, null, "   ");   // blank is treated the same as absent

        verify(transactions, never()).findByPhnoAndIdempotencyKey(any(Long.class), any());
        verify(bank, times(2)).transfer(any(Long.class), any(Long.class), any(), any());
    }

    // A transient blip (a dropped connection, a momentary pool exhaustion) is the realistic case this retry
    // actually exists for: the save fails once but succeeds on a later attempt, so the true COMPLETED status
    // still ends up recorded - nothing is lost, and the caller never even sees an error.
    @Test
    void ifTheFinalSaveFailsOnce_itIsRetried_andTheTrueStatusStillGetsRecorded() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new RuntimeException("db down")).doAnswer(inv -> inv.getArgument(0))
                .when(transactions).save(any(Transaction.class));

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null);

        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        verify(transactions, times(2)).save(any(Transaction.class));
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");
    }

    // The bug this guards against: the bank confirms the money moved, but the database never records that -
    // returning a COMPLETED-looking object anyway would tell the caller (and the receiver's own transaction
    // list, which is gated on the DB's own status) that it succeeded when the database does not reflect that at
    // all. The caller must instead see the same honest uncertainty as an outcome the bank itself never confirmed.
    @Test
    void ifTheFinalStatusCanNeverBeSaved_theCallerIsToldTheUncertainty_notAFabricatedSuccess() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new RuntimeException("db down")).when(transactions).save(any(Transaction.class));

        TransferFailedException ex = assertThrows(TransferFailedException.class,
                () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertTrue(ex.getMessage().contains("check your balance") || ex.getMessage().contains("Check your balance"), ex.getMessage());
        verify(transactions, times(3)).save(any(Transaction.class));   // gave up after 3 attempts
        verify(bank).transfer(PAYER, RECEIVER, AMOUNT, "phonepe-100000");   // the money still really moved
    }

    // FAILED is different: nothing moved, and the caller is about to see the real reason why (the exception the
    // bank actually threw) - so a save failure here is a bookkeeping loose end, not a false claim of success,
    // and must not replace that real reason with a generic "could not record" error.
    @Test
    void ifASaveOfAFailedStatusCannotBeWritten_theOriginalFailureReasonStillReachesTheCaller() {
        when(transactions.findMaxTransactionId()).thenReturn(null);
        doThrow(new UserNotExistException("User not found")).when(bank).transfer(eq(PAYER), eq(RECEIVER), any(), any());
        doThrow(new RuntimeException("db down")).when(transactions).save(any(Transaction.class));

        UserNotExistException ex = assertThrows(UserNotExistException.class,
                () -> service.sendMoney(PAYER, RECEIVER, AMOUNT, null, null));

        assertEquals("User not found", ex.getMessage());
    }

    // ============ makePayment ============

    @Test
    void makePayment_takesTheMoneyAndRecordsAPaymentWithNoReceiver() {
        Transaction t = service.makePayment(PAYER, new BigDecimal("99.5"), null, null);

        verify(bank).withdraw(PAYER, new BigDecimal("99.50"));
        verify(bank, never()).deposit(any(Long.class), any());
        assertEquals("Payment", t.getMode());
        assertNull(t.getReceiverPhno());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.COMPLETED), writes);
    }

    // ============ makePayment: idempotency key ============

    @Test
    void makePayment_sameIdempotencyKeyAndSameAmount_returnsTheExistingTransaction_withoutCallingTheBankAgain() {
        Transaction existing = new Transaction();
        existing.setTransactionId(100000);
        existing.setPhno(PAYER);
        existing.setAmount(AMOUNT);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "key-1")).thenReturn(Optional.of(existing));

        Transaction t = service.makePayment(PAYER, AMOUNT, null, "key-1");

        assertEquals(existing, t);
        verifyNoInteractions(bank);
    }

    @Test
    void makePayment_sameIdempotencyKey_differentAmount_throwsInvalidRequest() {
        Transaction existing = new Transaction();
        existing.setPhno(PAYER);
        existing.setAmount(new BigDecimal("999.00"));
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "key-1")).thenReturn(Optional.of(existing));

        assertThrows(InvalidRequestException.class, () -> service.makePayment(PAYER, AMOUNT, null, "key-1"));
        verifyNoInteractions(bank);
    }

    @Test
    void makePayment_storesTheNote_trimmedAndBlankAsNull() {
        assertEquals("movie tickets", service.makePayment(PAYER, AMOUNT, "  movie tickets  ", null).getNote());
        assertEquals(null, service.makePayment(PAYER, AMOUNT, "", null).getNote());
        assertEquals(null, service.makePayment(PAYER, AMOUNT, null, null).getNote());
    }

    @Test
    void makePayment_insufficientFunds_isRecordedAsFailed() {
        doThrow(new BalanceException("Insufficient Funds")).when(bank).withdraw(eq(PAYER), any());

        assertThrows(BalanceException.class, () -> service.makePayment(PAYER, AMOUNT, null, null));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void makePayment_outcomeUnknown_needsAPerson() {
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).withdraw(eq(PAYER), any());

        assertThrows(TransferFailedException.class, () -> service.makePayment(PAYER, AMOUNT, null, null));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "2.345"})
    void makePayment_badAmount_isRejected(String amount) {
        assertThrows(InvalidRequestException.class, () -> service.makePayment(PAYER, new BigDecimal(amount), null, null));

        verifyNoInteractions(bank, transactions);
    }

    // ============ history: people only ever see their own money ============

    // The visibility rule itself (a receiver never sees an unfinished payment) now lives in the repository query
    // (TransactionRepository.findVisibleTo) and is tested there; this service just wires page/size into a Pageable.
    @Test
    void transactionsOf_asksTheRepositoryForANewestFirstPage() {
        when(transactions.findVisibleTo(eq(RECEIVER), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                row(1, PAYER, RECEIVER, TransactionStatus.COMPLETED))));

        PageResponse<Transaction> result = service.transactionsOf(RECEIVER, 0, 20, null, null, null, null);

        assertEquals(List.of(1L), result.content().stream().map(Transaction::getTransactionId).toList());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).findVisibleTo(eq(RECEIVER), any(), any(), any(), any(), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(Sort.by("id").descending(), pageable.getValue().getSort());
    }

    @Test
    void transactionsOf_passesFromAndToThrough() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");
        when(transactions.findVisibleTo(eq(RECEIVER), eq(from), eq(to), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.transactionsOf(RECEIVER, 0, 20, from, to, null, null);

        verify(transactions).findVisibleTo(eq(RECEIVER), eq(from), eq(to), any(), any(), any());
    }

    @Test
    void transactionsOf_passesCounterpartyAndNoteContainsThrough() {
        when(transactions.findVisibleTo(eq(RECEIVER), any(), any(), eq(PAYER), eq("rent"), any())).thenReturn(new PageImpl<>(List.of()));

        service.transactionsOf(RECEIVER, 0, 20, null, null, PAYER, "rent");

        verify(transactions).findVisibleTo(eq(RECEIVER), any(), any(), eq(PAYER), eq("rent"), any());
    }

    @Test
    void transactionsOf_fromAfterTo_throwsInvalidRequest() {
        Instant from = Instant.parse("2026-01-31T23:59:59Z");
        Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(InvalidRequestException.class, () -> service.transactionsOf(RECEIVER, 0, 20, from, to, null, null));

        verifyNoInteractions(transactions);
    }

    @Test
    void transactionsOf_negativePage_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> service.transactionsOf(RECEIVER, -1, 20, null, null, null, null));

        verifyNoInteractions(transactions);
    }

    @Test
    void transactionsOf_sizeOutOfRange_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> service.transactionsOf(RECEIVER, 0, 0, null, null, null, null));
        assertThrows(InvalidRequestException.class, () -> service.transactionsOf(RECEIVER, 0, PhonepeService.MAX_PAGE_SIZE + 1, null, null, null, null));

        verifyNoInteractions(transactions);
    }

    // ============ monthly summary ============

    // The fixed clock in setUp() is 2026-09-21T10:00:00Z, so "no month given" must resolve to September 2026.
    @Test
    void monthlySummary_defaultsToTheCurrentMonth_whenNoneGiven() {
        Instant septemberStart = Instant.parse("2026-09-01T00:00:00Z");
        Instant septemberEnd = Instant.parse("2026-09-30T23:59:59.999999999Z");
        when(transactions.sumSent(eq(RECEIVER), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumReceived(eq(RECEIVER), any(), any())).thenReturn(BigDecimal.ZERO);

        MonthlySummaryResponse result = service.monthlySummary(RECEIVER, null);

        assertEquals("2026-09", result.month());
        verify(transactions).sumSent(RECEIVER, septemberStart, septemberEnd);
        verify(transactions).sumReceived(RECEIVER, septemberStart, septemberEnd);
        verify(transactions).countSent(RECEIVER, septemberStart, septemberEnd);
        verify(transactions).countReceived(RECEIVER, septemberStart, septemberEnd);
    }

    @Test
    void monthlySummary_blankMonth_alsoDefaultsToTheCurrentMonth() {
        when(transactions.sumSent(eq(RECEIVER), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumReceived(eq(RECEIVER), any(), any())).thenReturn(BigDecimal.ZERO);

        assertEquals("2026-09", service.monthlySummary(RECEIVER, "  ").month());
    }

    @Test
    void monthlySummary_parsesAnExplicitMonth() {
        Instant januaryStart = Instant.parse("2026-01-01T00:00:00Z");
        Instant januaryEnd = Instant.parse("2026-01-31T23:59:59.999999999Z");
        when(transactions.sumSent(eq(RECEIVER), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.sumReceived(eq(RECEIVER), any(), any())).thenReturn(BigDecimal.ZERO);

        MonthlySummaryResponse result = service.monthlySummary(RECEIVER, "2026-01");

        assertEquals("2026-01", result.month());
        verify(transactions).sumSent(RECEIVER, januaryStart, januaryEnd);
    }

    @Test
    void monthlySummary_invalidMonthFormat_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> service.monthlySummary(RECEIVER, "not-a-month"));

        verifyNoInteractions(transactions);
    }

    @Test
    void monthlySummary_roundsAmountsToTwoDecimals_andPassesCountsThrough() {
        when(transactions.sumSent(eq(RECEIVER), any(), any())).thenReturn(new BigDecimal("12.3"));
        when(transactions.sumReceived(eq(RECEIVER), any(), any())).thenReturn(new BigDecimal("45"));
        when(transactions.countSent(eq(RECEIVER), any(), any())).thenReturn(3L);
        when(transactions.countReceived(eq(RECEIVER), any(), any())).thenReturn(7L);

        MonthlySummaryResponse result = service.monthlySummary(RECEIVER, "2026-01");

        assertEquals(new BigDecimal("12.30"), result.totalSent());
        assertEquals(3, result.sentCount());
        assertEquals(new BigDecimal("45.00"), result.totalReceived());
        assertEquals(7, result.receivedCount());
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

    // ============ refund ============

    private Transaction payment(long transactionId, long payer, TransactionStatus status) {
        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        t.setPhno(payer);
        t.setReceiverPhno(null);
        t.setMode("Payment");
        t.setStatus(status);
        t.setAmount(AMOUNT);
        return t;
    }

    @Test
    void refund_creditsTheOriginalAmountBackAndRecordsARefundRow() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        when(transactions.sumRefundedAmount(100000)).thenReturn(BigDecimal.ZERO);

        Transaction t = service.refund(PAYER, 100000, null);

        verify(bank).deposit(PAYER, AMOUNT);
        verify(bank, never()).withdraw(any(Long.class), any());
        assertEquals("Refund", t.getMode());
        assertEquals(PAYER, t.getPhno());
        assertNull(t.getReceiverPhno());
        assertEquals(100000L, t.getRefundOfTransactionId());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.COMPLETED), writes);
    }

    @Test
    void refund_ofSomeoneElsesPayment_isNotFound_notForbidden() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, STRANGER, TransactionStatus.COMPLETED)));

        assertThrows(TransactionNotFoundException.class, () -> service.refund(PAYER, 100000, null));
        verifyNoInteractions(bank);
    }

    @Test
    void refund_ofAP2PTransfer_isRejected() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(row(100000, PAYER, RECEIVER, TransactionStatus.COMPLETED)));

        assertThrows(InvalidRequestException.class, () -> service.refund(PAYER, 100000, null));
        verifyNoInteractions(bank);
    }

    @Test
    void refund_ofAPendingPayment_isRejected() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.PENDING)));

        assertThrows(InvalidRequestException.class, () -> service.refund(PAYER, 100000, null));
        verifyNoInteractions(bank);
    }

    @Test
    void refund_ofAnAlreadyRefundedPayment_isRejected() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        when(transactions.sumRefundedAmount(100000)).thenReturn(AMOUNT);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class, () -> service.refund(PAYER, 100000, null));

        assertEquals("This payment has already been refunded", ex.getMessage());
        verifyNoInteractions(bank);
    }

    @Test
    void refund_sameIdempotencyKeyAndSameOriginal_returnsTheExistingRefund_withoutCallingTheBankAgain() {
        Transaction existing = new Transaction();
        existing.setTransactionId(100001);
        existing.setPhno(PAYER);
        existing.setRefundOfTransactionId(100000L);
        existing.setAmount(AMOUNT);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "refund-key")).thenReturn(Optional.of(existing));

        Transaction t = service.refund(PAYER, 100000, "refund-key");

        assertEquals(existing, t);
        verifyNoInteractions(bank);
    }

    @Test
    void refund_sameIdempotencyKey_differentOriginal_throwsInvalidRequest() {
        Transaction existing = new Transaction();
        existing.setPhno(PAYER);
        existing.setRefundOfTransactionId(999999L);
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "refund-key")).thenReturn(Optional.of(existing));

        assertThrows(InvalidRequestException.class, () -> service.refund(PAYER, 100000, "refund-key"));
        verifyNoInteractions(bank);
    }

    @Test
    void refund_bankOutcomeUnknown_needsAPerson_andLeavesTheOriginalPaymentCompleted() {
        Transaction original = payment(100000, PAYER, TransactionStatus.COMPLETED);
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(original));
        when(transactions.sumRefundedAmount(100000)).thenReturn(BigDecimal.ZERO);
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).deposit(eq(PAYER), any());

        assertThrows(TransferFailedException.class, () -> service.refund(PAYER, 100000, null));

        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
        assertEquals(TransactionStatus.COMPLETED, original.getStatus());
    }

    // ============ partial refunds ============

    @Test
    void refund_partialAmount_creditsOnlyThatMuch() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        when(transactions.sumRefundedAmount(100000)).thenReturn(BigDecimal.ZERO);

        Transaction t = service.refund(PAYER, 100000, new BigDecimal("100.00"), null);

        verify(bank).deposit(PAYER, new BigDecimal("100.00"));
        assertEquals(new BigDecimal("100.00"), t.getAmount());
        assertEquals(100000L, t.getRefundOfTransactionId());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void refund_takesTheRowLockBeforeCheckingWhatIsLeft() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        when(transactions.sumRefundedAmount(100000)).thenReturn(BigDecimal.ZERO);

        service.refund(PAYER, 100000, new BigDecimal("10.00"), null);

        var order = inOrder(transactions);
        order.verify(transactions).lockByTransactionId(100000);
        order.verify(transactions).sumRefundedAmount(100000);
        order.verify(transactions).saveAndFlush(any(Transaction.class));
    }

    @Test
    void refund_aSecondPartialRefundUpToWhatIsLeftIsAllowed() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        when(transactions.sumRefundedAmount(100000)).thenReturn(new BigDecimal("100.00"));

        Transaction t = service.refund(PAYER, 100000, new BigDecimal("150.00"), null);

        verify(bank).deposit(PAYER, new BigDecimal("150.00"));
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void refund_moreThanWhatIsLeft_isRejectedBeforeTheBank() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        when(transactions.sumRefundedAmount(100000)).thenReturn(new BigDecimal("200.00"));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.refund(PAYER, 100000, new BigDecimal("50.01"), null));

        assertEquals("Refund amount is more than what is left to refund on this payment (50.00)", ex.getMessage());
        verifyNoInteractions(bank);
        assertTrue(writes.isEmpty());
    }

    @Test
    void refund_withNoAmount_refundsExactlyWhatIsLeft() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        when(transactions.sumRefundedAmount(100000)).thenReturn(new BigDecimal("100.00"));

        Transaction t = service.refund(PAYER, 100000, null, null);

        verify(bank).deposit(PAYER, new BigDecimal("150.00"));
        assertEquals(new BigDecimal("150.00"), t.getAmount());
    }

    @Test
    void refund_ofAZeroOrNegativeAmount_isRejected() {
        assertThrows(InvalidRequestException.class, () -> service.refund(PAYER, 100000, BigDecimal.ZERO, null));
        assertThrows(InvalidRequestException.class, () -> service.refund(PAYER, 100000, new BigDecimal("-1"), null));
        verifyNoInteractions(bank);
    }

    @Test
    void refund_sameIdempotencyKey_differentAmount_throwsInvalidRequest() {
        Transaction existing = new Transaction();
        existing.setPhno(PAYER);
        existing.setRefundOfTransactionId(100000L);
        existing.setAmount(new BigDecimal("100.00"));
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "refund-key")).thenReturn(Optional.of(existing));

        assertThrows(InvalidRequestException.class,
                () -> service.refund(PAYER, 100000, new BigDecimal("40.00"), "refund-key"));
        verifyNoInteractions(bank);
    }

    @Test
    void refund_aSameKeyRetryThatRacedInIsFoundUnderTheLock_andTheBankIsNotCalledAgain() {
        when(transactions.findByTransactionId(100000)).thenReturn(Optional.of(payment(100000, PAYER, TransactionStatus.COMPLETED)));
        Transaction racedIn = new Transaction();
        racedIn.setPhno(PAYER);
        racedIn.setRefundOfTransactionId(100000L);
        racedIn.setAmount(new BigDecimal("100.00"));
        racedIn.setStatus(TransactionStatus.COMPLETED);
        // Not there on the first (unlocked) look, there once the lock is held.
        when(transactions.findByPhnoAndIdempotencyKey(PAYER, "refund-key"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(racedIn));

        Transaction t = service.refund(PAYER, 100000, new BigDecimal("100.00"), "refund-key");

        assertEquals(racedIn, t);
        verifyNoInteractions(bank);
    }
}
