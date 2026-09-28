package com.example.phonepayservice.service;

import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.entity.UpiCollectRequest;
import com.example.phonepayservice.entity.UpiCollectRequestStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.exception.UpiCollectRequestNotFoundException;
import com.example.phonepayservice.repository.UpiCollectRequestRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpiCollectRequestServiceTest {

    private static final long PAYER = 9123456789L;
    private static final String UPI_ID = PAYER + "@charanpe";
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("250.00");
    private static final String REF = "OrderService-42";

    private static class FixedClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW; }
    }

    @Mock
    private UpiCollectRequestRepository requests;
    @Mock
    private PhonepeService phonepeService;

    private UpiCollectRequestService service;

    @BeforeEach
    void setUp() {
        service = new UpiCollectRequestService(requests, phonepeService, new FixedClock(), 4);
    }

    private UpiCollectRequest stored(long id, UpiCollectRequestStatus status, Instant expiresAt) {
        UpiCollectRequest r = new UpiCollectRequest();
        r.setId(id);
        r.setMerchantReference(REF);
        r.setPayerPhno(PAYER);
        r.setAmount(AMOUNT);
        r.setStatus(status);
        r.setCreatedAt(NOW);
        r.setExpiresAt(expiresAt);
        return r;
    }

    private Transaction completedTransaction(long transactionId) {
        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        t.setStatus(TransactionStatus.COMPLETED);
        return t;
    }

    // ---------- create ----------

    @Test
    void create_savesAPendingRequest_resolvedFromTheUpiId() {
        when(requests.findByMerchantReference(REF)).thenReturn(Optional.empty());
        when(requests.save(any(UpiCollectRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        UpiCollectRequest result = service.create(REF, UPI_ID, AMOUNT, "Order payment");

        assertEquals(UpiCollectRequestStatus.PENDING, result.getStatus());
        assertEquals(PAYER, result.getPayerPhno());
        assertEquals(NOW, result.getCreatedAt());
        assertEquals(NOW.plusSeconds(4 * 60), result.getExpiresAt());
    }

    @Test
    void create_sameMerchantReferenceTwice_returnsTheExistingRequest_withoutCreatingADuplicate() {
        UpiCollectRequest existing = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        when(requests.findByMerchantReference(REF)).thenReturn(Optional.of(existing));

        UpiCollectRequest result = service.create(REF, UPI_ID, AMOUNT, "Order payment");

        assertEquals(existing, result);
        verify(requests, never()).save(any());
    }

    @Test
    void create_malformedUpiId_throwsInvalidRequest_savesNothing() {
        when(requests.findByMerchantReference(REF)).thenReturn(Optional.empty());

        assertThrows(InvalidRequestException.class, () -> service.create(REF, "not-a-upi-id", AMOUNT, null));

        verify(requests, never()).save(any());
    }

    // ---------- approve ----------

    @Test
    void approve_chargesTheBuyer_andMarksTheRequestApproved() {
        UpiCollectRequest pending = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        when(requests.findById(1L)).thenReturn(Optional.of(pending));
        when(phonepeService.makePayment(eq(PAYER), eq(AMOUNT), any(), eq("upi-collect-1")))
                .thenReturn(completedTransaction(555));
        when(requests.save(any(UpiCollectRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        UpiCollectRequest result = service.approve(PAYER, 1);

        assertEquals(UpiCollectRequestStatus.APPROVED, result.getStatus());
        assertEquals(555L, result.getResultTransactionId());
        assertEquals(NOW, result.getResolvedAt());
    }

    // Same reasoning as MoneyRequestService: an idempotency-key retry can hand back an existing, not-COMPLETED
    // transaction instead of throwing - the request must stay PENDING, not be marked APPROVED against a payment
    // that didn't actually go through.
    @Test
    void approve_paymentDoesNotComplete_leavesTheRequestPending() {
        UpiCollectRequest pending = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        when(requests.findById(1L)).thenReturn(Optional.of(pending));
        Transaction stillPending = new Transaction();
        stillPending.setTransactionId(555);
        stillPending.setStatus(TransactionStatus.NEEDS_RECONCILIATION);
        when(phonepeService.makePayment(eq(PAYER), eq(AMOUNT), any(), eq("upi-collect-1"))).thenReturn(stillPending);

        assertThrows(TransferFailedException.class, () -> service.approve(PAYER, 1));

        verify(requests, never()).save(any());
        assertEquals(UpiCollectRequestStatus.PENDING, pending.getStatus());
    }

    @Test
    void approve_pastItsDeadline_throwsInvalidRequest_withoutChargingAnything() {
        UpiCollectRequest expired = stored(1, UpiCollectRequestStatus.PENDING, NOW.minusSeconds(1));
        when(requests.findById(1L)).thenReturn(Optional.of(expired));
        when(requests.save(any(UpiCollectRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(InvalidRequestException.class, () -> service.approve(PAYER, 1));

        verifyNoInteractions(phonepeService);
        assertEquals(UpiCollectRequestStatus.EXPIRED, expired.getStatus());
    }

    @Test
    void approve_notTheRequestsPayer_throwsNotFound() {
        UpiCollectRequest pending = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        when(requests.findById(1L)).thenReturn(Optional.of(pending));

        assertThrows(UpiCollectRequestNotFoundException.class, () -> service.approve(9000000001L, 1));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void approve_alreadyResolved_throwsInvalidRequest_withoutChargingAgain() {
        UpiCollectRequest approved = stored(1, UpiCollectRequestStatus.APPROVED, NOW.plusSeconds(240));
        when(requests.findById(1L)).thenReturn(Optional.of(approved));

        assertThrows(InvalidRequestException.class, () -> service.approve(PAYER, 1));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void approve_doesNotExist_throwsNotFound() {
        when(requests.findById(1L)).thenReturn(Optional.empty());

        assertThrows(UpiCollectRequestNotFoundException.class, () -> service.approve(PAYER, 1));
    }

    // ---------- decline ----------

    @Test
    void decline_marksItDeclined_withoutMovingAnyMoney() {
        UpiCollectRequest pending = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        when(requests.findById(1L)).thenReturn(Optional.of(pending));
        when(requests.save(any(UpiCollectRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        UpiCollectRequest result = service.decline(PAYER, 1);

        assertEquals(UpiCollectRequestStatus.DECLINED, result.getStatus());
        assertEquals(NOW, result.getResolvedAt());
        verifyNoInteractions(phonepeService);
    }

    @Test
    void decline_notTheRequestsPayer_throwsNotFound() {
        UpiCollectRequest pending = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        when(requests.findById(1L)).thenReturn(Optional.of(pending));

        assertThrows(UpiCollectRequestNotFoundException.class, () -> service.decline(9000000001L, 1));
    }

    // ---------- getByMerchantReference / listPendingFor (lazy expiry) ----------

    @Test
    void getByMerchantReference_pastItsDeadline_isReportedAsExpired() {
        UpiCollectRequest expired = stored(1, UpiCollectRequestStatus.PENDING, NOW.minusSeconds(1));
        when(requests.findByMerchantReference(REF)).thenReturn(Optional.of(expired));
        ArgumentCaptor<UpiCollectRequest> captor = ArgumentCaptor.forClass(UpiCollectRequest.class);
        when(requests.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        UpiCollectRequest result = service.getByMerchantReference(REF);

        assertEquals(UpiCollectRequestStatus.EXPIRED, result.getStatus());
        assertEquals(UpiCollectRequestStatus.EXPIRED, captor.getValue().getStatus());
    }

    @Test
    void getByMerchantReference_notYetDue_staysPending() {
        UpiCollectRequest pending = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        when(requests.findByMerchantReference(REF)).thenReturn(Optional.of(pending));

        UpiCollectRequest result = service.getByMerchantReference(REF);

        assertEquals(UpiCollectRequestStatus.PENDING, result.getStatus());
        verify(requests, never()).save(any());
    }

    @Test
    void listPendingFor_excludesRequestsThatHaveJustExpired() {
        UpiCollectRequest stillPending = stored(1, UpiCollectRequestStatus.PENDING, NOW.plusSeconds(240));
        UpiCollectRequest nowExpired = stored(2, UpiCollectRequestStatus.PENDING, NOW.minusSeconds(1));
        when(requests.findByPayerPhnoAndStatusOrderByIdDesc(PAYER, UpiCollectRequestStatus.PENDING))
                .thenReturn(List.of(stillPending, nowExpired));
        when(requests.save(any(UpiCollectRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        List<UpiCollectRequest> result = service.listPendingFor(PAYER);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(UpiCollectRequestStatus.EXPIRED, nowExpired.getStatus());
    }
}
