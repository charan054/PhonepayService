package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.RecurringPaymentResponse;
import com.example.phonepayservice.entity.RecurringPayment;
import com.example.phonepayservice.entity.RecurringPaymentStatus;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.RecurringPaymentNotFoundException;
import com.example.phonepayservice.exception.UserNotExistException;
import com.example.phonepayservice.repository.RecurringPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringPaymentServiceTest {

    private static final long OWNER = 9876543210L;
    private static final long PAYEE = 9123456789L;
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static class FixedClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW; }
    }

    @Mock
    private RecurringPaymentRepository recurring;
    @Mock
    private BankGateway bank;
    @Mock
    private PhonepeService phonepeService;

    private RecurringPaymentService service;

    @BeforeEach
    void setUp() {
        service = new RecurringPaymentService(recurring, bank, phonepeService, new FixedClock());
    }

    private RecurringPayment stored(long id, long ownerPhno, long payeePhno, int intervalDays, RecurringPaymentStatus status, Instant nextRunAt) {
        RecurringPayment r = new RecurringPayment();
        r.setId(id);
        r.setOwnerPhno(ownerPhno);
        r.setPayeePhno(payeePhno);
        r.setAmount(AMOUNT);
        r.setIntervalDays(intervalDays);
        r.setStatus(status);
        r.setCreatedAt(NOW);
        r.setNextRunAt(nextRunAt);
        return r;
    }

    private Transaction completedTransaction() {
        Transaction t = new Transaction();
        t.setStatus(TransactionStatus.COMPLETED);
        return t;
    }

    // ---------- create ----------

    @Test
    void create_savesAnActiveRecurringPayment_withNextRunOneIntervalFromNow() {
        when(bank.findUser(PAYEE)).thenReturn(new BankUser());
        when(recurring.save(any(RecurringPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        RecurringPaymentResponse response = service.create(OWNER, PAYEE, AMOUNT, "rent", 7);

        assertEquals("ACTIVE", response.status());
        assertEquals(NOW.plusSeconds(7L * 24 * 3600), response.nextRunAt());
        ArgumentCaptor<RecurringPayment> captor = ArgumentCaptor.forClass(RecurringPayment.class);
        verify(recurring).save(captor.capture());
        assertEquals(OWNER, captor.getValue().getOwnerPhno());
        assertEquals(NOW, captor.getValue().getCreatedAt());
    }

    @Test
    void create_yourself_throwsInvalidRequest_withoutAskingTheBank() {
        assertThrows(InvalidRequestException.class, () -> service.create(OWNER, OWNER, AMOUNT, null, 7));

        verifyNoInteractions(bank, recurring);
    }

    @Test
    void create_bankDoesNotRecognizeThePayee_throwsUserNotExist_andSavesNothing() {
        when(bank.findUser(PAYEE)).thenThrow(new UserNotExistException("User not found"));

        assertThrows(UserNotExistException.class, () -> service.create(OWNER, PAYEE, AMOUNT, null, 7));

        verify(recurring, never()).save(any());
    }

    // ---------- pause / resume / cancel ----------

    @Test
    void pause_anActiveOne_pausesIt() {
        RecurringPayment active = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.ACTIVE, NOW.plusSeconds(1000));
        when(recurring.findById(1L)).thenReturn(Optional.of(active));
        when(recurring.save(any(RecurringPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("PAUSED", service.pause(OWNER, 1).status());
    }

    @Test
    void pause_notOwnedByCaller_throwsNotFound() {
        RecurringPayment active = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.ACTIVE, NOW.plusSeconds(1000));
        when(recurring.findById(1L)).thenReturn(Optional.of(active));

        assertThrows(RecurringPaymentNotFoundException.class, () -> service.pause(9000000001L, 1));
    }

    @Test
    void pause_alreadyPaused_throwsInvalidRequest() {
        RecurringPayment paused = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.PAUSED, NOW.plusSeconds(1000));
        when(recurring.findById(1L)).thenReturn(Optional.of(paused));

        assertThrows(InvalidRequestException.class, () -> service.pause(OWNER, 1));
    }

    // A long-paused payment must not come back scheduled for a moment deep in the past - that would make every
    // day of the pause look "overdue" and run all at once the moment the scheduler next ticks.
    @Test
    void resume_recomputesNextRunAtFromNow_notFromWhenItWasPaused() {
        RecurringPayment paused = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.PAUSED, Instant.parse("2020-01-01T00:00:00Z"));
        when(recurring.findById(1L)).thenReturn(Optional.of(paused));
        when(recurring.save(any(RecurringPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        RecurringPaymentResponse response = service.resume(OWNER, 1);

        assertEquals("ACTIVE", response.status());
        assertEquals(NOW.plusSeconds(7L * 24 * 3600), response.nextRunAt());
    }

    @Test
    void resume_notPaused_throwsInvalidRequest() {
        RecurringPayment active = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.ACTIVE, NOW.plusSeconds(1000));
        when(recurring.findById(1L)).thenReturn(Optional.of(active));

        assertThrows(InvalidRequestException.class, () -> service.resume(OWNER, 1));
    }

    @Test
    void cancel_marksItCancelled() {
        RecurringPayment active = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.ACTIVE, NOW.plusSeconds(1000));
        when(recurring.findById(1L)).thenReturn(Optional.of(active));
        when(recurring.save(any(RecurringPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("CANCELLED", service.cancel(OWNER, 1).status());
    }

    @Test
    void cancel_alreadyCancelled_throwsInvalidRequest() {
        RecurringPayment cancelled = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.CANCELLED, NOW.plusSeconds(1000));
        when(recurring.findById(1L)).thenReturn(Optional.of(cancelled));

        assertThrows(InvalidRequestException.class, () -> service.cancel(OWNER, 1));
    }

    // ---------- the scheduler: runDuePayments ----------

    @Test
    void runDuePayments_paysEachDueOne_andAdvancesItsNextRunAt() {
        RecurringPayment due = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.ACTIVE, NOW);
        when(recurring.findByStatusAndNextRunAtLessThanEqual(RecurringPaymentStatus.ACTIVE, NOW)).thenReturn(List.of(due));
        when(phonepeService.sendMoney(eq(OWNER), eq(PAYEE), eq(AMOUNT), any(), eq("recurring-1-" + NOW))).thenReturn(completedTransaction());
        when(recurring.save(any(RecurringPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.runDuePayments();

        ArgumentCaptor<RecurringPayment> captor = ArgumentCaptor.forClass(RecurringPayment.class);
        verify(recurring).save(captor.capture());
        assertEquals(RecurringPaymentStatus.ACTIVE, captor.getValue().getStatus());
        assertEquals(NOW.plusSeconds(7L * 24 * 3600), captor.getValue().getNextRunAt());
        assertEquals(NOW, captor.getValue().getLastRunAt());
    }

    @Test
    void runDuePayments_nothingDue_doesNothing() {
        when(recurring.findByStatusAndNextRunAtLessThanEqual(RecurringPaymentStatus.ACTIVE, NOW)).thenReturn(List.of());

        service.runDuePayments();

        verifyNoInteractions(phonepeService);
        verify(recurring, never()).save(any());
    }

    // sendMoney's idempotency-key lookup can hand back an existing, not-COMPLETED transaction from an earlier
    // failed attempt instead of retrying - that must pause the payment, not advance it as if it had succeeded.
    @Test
    void runDuePayments_sendMoneyReturnsANonCompletedTransaction_pausesIt_withoutAdvancingNextRunAt() {
        Instant dueAt = NOW;
        RecurringPayment due = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.ACTIVE, dueAt);
        when(recurring.findByStatusAndNextRunAtLessThanEqual(RecurringPaymentStatus.ACTIVE, NOW)).thenReturn(List.of(due));
        Transaction stillPending = new Transaction();
        stillPending.setStatus(TransactionStatus.NEEDS_RECONCILIATION);
        when(phonepeService.sendMoney(eq(OWNER), eq(PAYEE), eq(AMOUNT), any(), anyString())).thenReturn(stillPending);
        when(recurring.save(any(RecurringPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.runDuePayments();

        ArgumentCaptor<RecurringPayment> captor = ArgumentCaptor.forClass(RecurringPayment.class);
        verify(recurring).save(captor.capture());
        assertEquals(RecurringPaymentStatus.PAUSED, captor.getValue().getStatus());
        assertEquals(dueAt, captor.getValue().getNextRunAt(), "must not advance past a run that didn't actually complete");
    }

    // A thrown exception (insufficient funds, the bank unavailable, ...) must pause the payment rather than
    // crash the whole scheduler tick or silently retry forever on every future tick.
    @Test
    void runDuePayments_sendMoneyThrows_pausesThatPayment_withoutStoppingOthers() {
        RecurringPayment failing = stored(1, OWNER, PAYEE, 7, RecurringPaymentStatus.ACTIVE, NOW);
        RecurringPayment healthy = stored(2, OWNER, 9000000001L, 7, RecurringPaymentStatus.ACTIVE, NOW);
        when(recurring.findByStatusAndNextRunAtLessThanEqual(RecurringPaymentStatus.ACTIVE, NOW)).thenReturn(List.of(failing, healthy));
        when(phonepeService.sendMoney(eq(OWNER), eq(PAYEE), any(), any(), anyString()))
                .thenThrow(new com.example.phonepayservice.exception.BalanceException("Insufficient funds"));
        when(phonepeService.sendMoney(eq(OWNER), eq(9000000001L), any(), any(), anyString())).thenReturn(completedTransaction());
        when(recurring.save(any(RecurringPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.runDuePayments();

        verify(recurring, times(2)).save(any());
        assertEquals(RecurringPaymentStatus.PAUSED, failing.getStatus());
        assertEquals(RecurringPaymentStatus.ACTIVE, healthy.getStatus());
    }
}
