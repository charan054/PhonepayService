package com.example.phonepayservice.service;

import com.example.phonepayservice.dto.DisputeDtos.DisputeResponse;
import com.example.phonepayservice.entity.PaymentDispute;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.DisputeNotFoundException;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.TransactionNotFoundException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.repository.PaymentDisputeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {
    private static final long CUSTOMER = 9876543210L;
    private static final long TXN = 100000L;
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private static class FixedClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW; }
    }

    @Mock
    private PaymentDisputeRepository disputes;
    @Mock
    private PhonepeService phonepe;

    private DisputeService service;

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputes, phonepe, new FixedClock());
    }

    private Transaction payment(long payer, String mode, TransactionStatus status, Instant createdAt) {
        Transaction t = new Transaction();
        t.setTransactionId(TXN);
        t.setPhno(payer);
        t.setMode(mode);
        t.setStatus(status);
        t.setAmount(new BigDecimal("250.00"));
        t.setCreatedAt(createdAt);
        return t;
    }

    private PaymentDispute open(long id) {
        PaymentDispute d = new PaymentDispute();
        d.setId(id);
        d.setTransactionId(TXN);
        d.setPhno(CUSTOMER);
        d.setStatus(PaymentDispute.OPEN);
        return d;
    }

    // ---------- raise ----------

    @Test
    void raisingADisputeOnYourOwnRecentCompletedPaymentSavesAnOpenOne() {
        when(phonepe.transaction(CUSTOMER, TXN)).thenReturn(payment(CUSTOMER, "Payment", TransactionStatus.COMPLETED, NOW.minusSeconds(3600)));
        when(disputes.findByTransactionId(TXN)).thenReturn(Optional.empty());
        when(disputes.save(any(PaymentDispute.class))).thenAnswer(i -> i.getArgument(0));

        DisputeResponse r = service.raise(CUSTOMER, TXN, "  Never got the item  ");

        assertEquals("OPEN", r.status());
        assertEquals("Never got the item", r.reason());
        assertEquals(CUSTOMER, r.phno());
        assertEquals(NOW, r.createdAt());
    }

    @Test
    void aBlankReasonIsRefusedBeforeAnythingIsLookedUp() {
        assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, "   "));
        assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, null));
        verify(disputes, never()).save(any());
    }

    @Test
    void anUnknownOrSomeoneElsesPaymentIsNotFound() {
        when(phonepe.transaction(CUSTOMER, TXN)).thenThrow(new TransactionNotFoundException("Transaction not found"));

        assertThrows(TransactionNotFoundException.class, () -> service.raise(CUSTOMER, TXN, "wrong"));
        verify(disputes, never()).save(any());
    }

    @Test
    void aPersonToPersonTransferCannotBeDisputed() {
        when(phonepe.transaction(CUSTOMER, TXN)).thenReturn(payment(CUSTOMER, "Transfer", TransactionStatus.COMPLETED, NOW));

        assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, "wrong"));
        verify(disputes, never()).save(any());
    }

    @Test
    void thePersonWhoReceivedARefundRowCannotDisputeSomeoneElsesPayment() {
        // visible to the receiver, but the payer is somebody else
        when(phonepe.transaction(CUSTOMER, TXN)).thenReturn(payment(9111111111L, "Payment", TransactionStatus.COMPLETED, NOW));

        assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, "wrong"));
    }

    @Test
    void aPaymentThatNeverCompletedCannotBeDisputed() {
        when(phonepe.transaction(CUSTOMER, TXN)).thenReturn(payment(CUSTOMER, "Payment", TransactionStatus.FAILED, NOW));

        assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, "wrong"));
    }

    @Test
    void aPaymentOlderThanThirtyDaysCannotBeDisputed() {
        when(phonepe.transaction(CUSTOMER, TXN)).thenReturn(payment(CUSTOMER, "Payment", TransactionStatus.COMPLETED, NOW.minusSeconds(31L * 24 * 3600)));

        assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, "wrong"));
        verify(disputes, never()).save(any());
    }

    @Test
    void aSecondDisputeOnTheSamePaymentIsRefused() {
        when(phonepe.transaction(CUSTOMER, TXN)).thenReturn(payment(CUSTOMER, "Payment", TransactionStatus.COMPLETED, NOW));
        when(disputes.findByTransactionId(TXN)).thenReturn(Optional.of(open(1)));

        assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, "wrong"));
        verify(disputes, never()).save(any());
    }

    @Test
    void aRaceOnTheUniqueConstraintReadsAsAlreadyDisputed() {
        when(phonepe.transaction(CUSTOMER, TXN)).thenReturn(payment(CUSTOMER, "Payment", TransactionStatus.COMPLETED, NOW));
        when(disputes.findByTransactionId(TXN)).thenReturn(Optional.empty());
        when(disputes.save(any(PaymentDispute.class))).thenThrow(new DataIntegrityViolationException("dup"));

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> service.raise(CUSTOMER, TXN, "wrong"));
        assertEquals("This payment already has a dispute", e.getMessage());
    }

    // ---------- resolve ----------

    @Test
    void approvingARefundsThePaymentWithAKeyTiedToTheDisputeAndRecordsTheRefund() {
        PaymentDispute d = open(7);
        when(disputes.findById(7L)).thenReturn(Optional.of(d));
        Transaction refund = payment(CUSTOMER, "Refund", TransactionStatus.COMPLETED, NOW);
        refund.setTransactionId(100005L);
        when(phonepe.merchantRefund(TXN, null, "dispute-7")).thenReturn(refund);
        when(disputes.save(any(PaymentDispute.class))).thenAnswer(i -> i.getArgument(0));

        DisputeResponse r = service.resolve(7, true, "Sorry about that");

        assertEquals("APPROVED", r.status());
        assertEquals(100005L, r.refundTransactionId());
        assertEquals("Sorry about that", r.resolutionNote());
        assertEquals(NOW, r.resolvedAt());
    }

    @Test
    void rejectingMovesNoMoney() {
        when(disputes.findById(7L)).thenReturn(Optional.of(open(7)));
        when(disputes.save(any(PaymentDispute.class))).thenAnswer(i -> i.getArgument(0));

        DisputeResponse r = service.resolve(7, false, "The item was delivered");

        assertEquals("REJECTED", r.status());
        assertNull(r.refundTransactionId());
        verify(phonepe, never()).merchantRefund(any(Long.class), any(), any());
    }

    @Test
    void aFailedRefundLeavesTheDisputeOpenSoItCanBeApprovedAgain() {
        PaymentDispute d = open(7);
        when(disputes.findById(7L)).thenReturn(Optional.of(d));
        when(phonepe.merchantRefund(eq(TXN), any(), eq("dispute-7"))).thenThrow(new TransferFailedException("bank down"));

        assertThrows(TransferFailedException.class, () -> service.resolve(7, true, null));

        assertEquals(PaymentDispute.OPEN, d.getStatus());
        verify(disputes, never()).save(any());
    }

    @Test
    void anAlreadyResolvedDisputeCannotBeResolvedAgain() {
        PaymentDispute d = open(7);
        d.setStatus(PaymentDispute.APPROVED);
        when(disputes.findById(7L)).thenReturn(Optional.of(d));

        assertThrows(InvalidRequestException.class, () -> service.resolve(7, true, null));
        verify(phonepe, never()).merchantRefund(any(Long.class), any(), any());
    }

    @Test
    void anUnknownDisputeIsNotFound() {
        when(disputes.findById(99L)).thenReturn(Optional.empty());

        assertThrows(DisputeNotFoundException.class, () -> service.resolve(99, true, null));
    }

    @Test
    void twoStaffResolvingAtOnceLoseTheSecondSaveCleanly() {
        when(disputes.findById(7L)).thenReturn(Optional.of(open(7)));
        when(disputes.save(any(PaymentDispute.class))).thenThrow(new ObjectOptimisticLockingFailureException(PaymentDispute.class, 7L));

        assertThrows(InvalidRequestException.class, () -> service.resolve(7, false, null));
    }

    // ---------- lists ----------

    @Test
    void mineAndTheOpenQueueMapTheRepositoryRows() {
        when(disputes.findByPhnoOrderByIdDesc(CUSTOMER)).thenReturn(List.of(open(2), open(1)));
        when(disputes.findByStatusOrderByIdAsc("OPEN")).thenReturn(List.of(open(1)));

        assertEquals(2, service.mine(CUSTOMER).size());
        assertEquals(1, service.openQueue().size());
    }
}
