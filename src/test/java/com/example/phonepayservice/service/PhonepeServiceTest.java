package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.LoginResponse;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
    void sendMoney_movesTheMoneyInTheRightOrderAndRecordsIt() {
        when(transactions.findMaxTransactionId()).thenReturn(null);

        Transaction t = service.sendMoney(PAYER, RECEIVER, new BigDecimal("250"));

        InOrder order = inOrder(bank, transactions);
        order.verify(bank).findUser(RECEIVER);                                // 1. the receiver must exist
        order.verify(transactions).saveAndFlush(any(Transaction.class));      // 2. write it down as PENDING
        order.verify(bank).withdraw(PAYER, AMOUNT);                           // 3. take the money
        order.verify(bank).deposit(RECEIVER, AMOUNT);                         // 4. give the money
        order.verify(transactions).save(any(Transaction.class));              // 5. write down that it completed
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

        when(transactions.findMaxTransactionId()).thenReturn(100007L);
        assertEquals(100008L, service.sendMoney(PAYER, RECEIVER, AMOUNT).getTransactionId());
    }

    // ============ sendMoney: bad requests never move money ============

    @Test
    void sendMoney_toAnUnknownNumber_neverTakesTheMoney() {
        // The old code took the sender's money FIRST and only then noticed the receiver did not exist.
        when(bank.findUser(RECEIVER)).thenThrow(new UserNotExistException("User not found"));

        assertThrows(UserNotExistException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        verify(bank, never()).withdraw(any(Long.class), any());
        verify(bank, never()).deposit(any(Long.class), any());
        verify(transactions, never()).saveAndFlush(any());
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

    // ============ sendMoney: the bank refuses to release the money ============

    @Test
    void sendMoney_insufficientFunds_failsWithoutTouchingTheReceiver() {
        doThrow(new BalanceException("Insufficient Funds")).when(bank).withdraw(eq(PAYER), any());

        BalanceException ex = assertThrows(BalanceException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertEquals("Insufficient Funds", ex.getMessage());
        verify(bank, never()).deposit(any(Long.class), any());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void sendMoney_bankUnreachableAtWithdrawal_failsCleanly() {
        doThrow(new BankUnavailableException("down", null)).when(bank).withdraw(eq(PAYER), any());

        assertThrows(BankUnavailableException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        verify(bank, never()).deposit(any(Long.class), any());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void sendMoney_withdrawalOutcomeUnknown_isNeverRefundedOrRetried() {
        // A timeout: the bank MAY have taken the money. Guessing either way could create or destroy money.
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).withdraw(eq(PAYER), any());
        when(transactions.findMaxTransactionId()).thenReturn(null);

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertTrue(ex.getMessage().contains("could not confirm"), ex.getMessage());
        assertTrue(ex.getMessage().contains("100000"), "the user needs the reference number: " + ex.getMessage());
        verify(bank, times(1)).withdraw(eq(PAYER), any());
        verify(bank, never()).deposit(any(Long.class), any());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
    }

    // ============ sendMoney: the receiver cannot be credited after the payer was charged ============

    @Test
    void sendMoney_receiverRefusesTheMoney_payerIsRefunded() {
        doThrow(new BalanceException("Amount too low")).when(bank).deposit(eq(RECEIVER), any());

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertEquals("The transfer could not be completed. Your money has been returned.", ex.getMessage());
        verify(bank).deposit(PAYER, AMOUNT);   // the refund
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.FAILED), writes);
    }

    @Test
    void sendMoney_receiverVanishedBetweenCheckAndCredit_payerIsRefunded() {
        doThrow(new UserNotExistException("User not found")).when(bank).deposit(eq(RECEIVER), any());

        assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        verify(bank).deposit(PAYER, AMOUNT);
    }

    @Test
    void sendMoney_receiverRefusesAndTheRefundFailsToo_needsAPerson() {
        doThrow(new BalanceException("Amount too low")).when(bank).deposit(eq(RECEIVER), any());
        doThrow(new BankUnavailableException("down", null)).when(bank).deposit(eq(PAYER), any());
        when(transactions.findMaxTransactionId()).thenReturn(null);

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertTrue(ex.getMessage().contains("could not return your money automatically"), ex.getMessage());
        assertTrue(ex.getMessage().contains("100000"), ex.getMessage());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
    }

    @Test
    void sendMoney_creditOutcomeUnknown_payerIsNotRefunded() {
        // The receiver MAY have been credited. Refunding now could pay the money out twice.
        doThrow(new BankOutcomeUnknownException("timeout", null)).when(bank).deposit(eq(RECEIVER), any());

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertTrue(ex.getMessage().contains("could not confirm that the money reached the receiver"), ex.getMessage());
        verify(bank, times(1)).deposit(eq(RECEIVER), any());
        verify(bank, never()).deposit(eq(PAYER), any());
        assertEquals(List.of(TransactionStatus.PENDING, TransactionStatus.NEEDS_RECONCILIATION), writes);
    }

    @Test
    void sendMoney_receiverBusyTwice_isRetriedAndSucceeds() {
        // "busy" means the bank changed nothing, so trying again can never credit twice
        doThrow(new BankConflictException("busy")).doThrow(new BankConflictException("busy")).doNothing()
                .when(bank).deposit(eq(RECEIVER), any());

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT);

        verify(bank, times(3)).deposit(RECEIVER, AMOUNT);
        verify(bank, never()).deposit(eq(PAYER), any());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void sendMoney_receiverStaysBusy_givesUpAndRefunds() {
        doThrow(new BankConflictException("busy")).when(bank).deposit(eq(RECEIVER), any());

        TransferFailedException ex = assertThrows(TransferFailedException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        assertEquals("The transfer could not be completed. Your money has been returned.", ex.getMessage());
        verify(bank, times(3)).deposit(RECEIVER, AMOUNT);
        verify(bank, times(1)).deposit(PAYER, AMOUNT);
    }

    // ============ bookkeeping ============

    @Test
    void ifThePaymentCannotBeWrittenDown_noMoneyMoves() {
        doThrow(new DataIntegrityViolationException("dup")).when(transactions).saveAndFlush(any(Transaction.class));
        when(transactions.findMaxTransactionId()).thenReturn(100000L);

        assertThrows(DataIntegrityViolationException.class, () -> service.sendMoney(PAYER, RECEIVER, AMOUNT));

        verify(transactions, times(5)).saveAndFlush(any(Transaction.class));   // gave up after 5 attempts
        verify(bank, never()).withdraw(any(Long.class), any());
    }

    @Test
    void transactionIdCollision_retriesWithTheNextFreeNumber() {
        // another request grabbed 100006 a moment ago; the unique constraint refused ours
        doThrow(new DataIntegrityViolationException("dup"))
                .doAnswer(inv -> inv.getArgument(0))
                .when(transactions).saveAndFlush(any(Transaction.class));
        when(transactions.findMaxTransactionId()).thenReturn(100005L, 100006L);

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT);

        assertEquals(100007L, t.getTransactionId());
        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
    }

    @Test
    void ifTheFinalStatusCannotBeSaved_thePaymentStillCountsAsDone() {
        // the money HAS moved; failing the request now would only hide that
        doThrow(new RuntimeException("db down")).when(transactions).save(any(Transaction.class));

        Transaction t = service.sendMoney(PAYER, RECEIVER, AMOUNT);

        assertEquals(TransactionStatus.COMPLETED, t.getStatus());
        verify(bank).withdraw(PAYER, AMOUNT);
        verify(bank).deposit(RECEIVER, AMOUNT);
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

    @Test
    void transactionsOf_showsWhatThePersonPaidAndWhatReallyReachedThem() {
        when(transactions.findByPhnoOrReceiverPhnoOrderByIdDesc(RECEIVER, RECEIVER)).thenReturn(List.of(
                row(1, RECEIVER, STRANGER, TransactionStatus.PENDING),               // their own payment: visible
                row(2, RECEIVER, STRANGER, TransactionStatus.FAILED),                // their own failed payment: visible
                row(3, PAYER, RECEIVER, TransactionStatus.FAILED),                   // someone's failed payment to them: hidden
                row(4, PAYER, RECEIVER, TransactionStatus.NEEDS_RECONCILIATION),     // still being settled: hidden
                row(5, PAYER, RECEIVER, TransactionStatus.PENDING),                  // not finished: hidden
                row(6, PAYER, RECEIVER, TransactionStatus.COMPLETED),                // money that reached them: visible
                row(7, PAYER, RECEIVER, null)));                                     // older row from before statuses existed: visible

        List<Long> visible = service.transactionsOf(RECEIVER).stream().map(Transaction::getTransactionId).toList();

        assertEquals(List.of(1L, 2L, 6L, 7L), visible);
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
