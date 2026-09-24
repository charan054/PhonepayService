package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.MoneyRequestResponse;
import com.example.phonepayservice.entity.MoneyRequest;
import com.example.phonepayservice.entity.MoneyRequestStatus;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.MoneyRequestNotFoundException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.exception.UserNotExistException;
import com.example.phonepayservice.repository.MoneyRequestRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MoneyRequestServiceTest {

    private static final long REQUESTER = 9876543210L;
    private static final long PAYER = 9123456789L;
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("250.00");

    private static class FixedClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW; }
    }

    @Mock
    private MoneyRequestRepository requests;
    @Mock
    private BankGateway bank;
    @Mock
    private PhonepeService phonepeService;

    private MoneyRequestService service;

    @BeforeEach
    void setUp() {
        service = new MoneyRequestService(requests, bank, phonepeService, new FixedClock());
    }

    private MoneyRequest stored(long id, long requesterPhno, long payerPhno, MoneyRequestStatus status) {
        MoneyRequest r = new MoneyRequest();
        r.setId(id);
        r.setRequesterPhno(requesterPhno);
        r.setPayerPhno(payerPhno);
        r.setAmount(AMOUNT);
        r.setStatus(status);
        r.setCreatedAt(NOW);
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
    void create_savesAPendingRequest_afterConfirmingThePayerIsReal() {
        when(bank.findUser(PAYER)).thenReturn(new BankUser());
        when(requests.save(any(MoneyRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        MoneyRequestResponse response = service.create(REQUESTER, PAYER, AMOUNT, "rent");

        assertEquals("PENDING", response.status());
        assertEquals("OUTGOING", response.direction());   // from the requester's own point of view
        ArgumentCaptor<MoneyRequest> captor = ArgumentCaptor.forClass(MoneyRequest.class);
        verify(requests).save(captor.capture());
        assertEquals(REQUESTER, captor.getValue().getRequesterPhno());
        assertEquals(PAYER, captor.getValue().getPayerPhno());
        assertEquals(NOW, captor.getValue().getCreatedAt());
    }

    @Test
    void create_yourself_throwsInvalidRequest_withoutAskingTheBank() {
        assertThrows(InvalidRequestException.class, () -> service.create(REQUESTER, REQUESTER, AMOUNT, null));

        verifyNoInteractions(bank, requests);
    }

    @Test
    void create_bankDoesNotRecognizeThePayer_throwsUserNotExist_andSavesNothing() {
        when(bank.findUser(PAYER)).thenThrow(new UserNotExistException("User not found"));

        assertThrows(UserNotExistException.class, () -> service.create(REQUESTER, PAYER, AMOUNT, null));

        verify(requests, never()).save(any());
    }

    // ---------- approve ----------

    @Test
    void approve_movesMoney_andMarksTheRequestApproved() {
        MoneyRequest pending = stored(1, REQUESTER, PAYER, MoneyRequestStatus.PENDING);
        when(requests.findById(1L)).thenReturn(Optional.of(pending));
        when(phonepeService.sendMoney(PAYER, REQUESTER, AMOUNT, null, "money-request-1")).thenReturn(completedTransaction(555));
        when(requests.save(any(MoneyRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        MoneyRequestResponse response = service.approve(PAYER, 1);

        assertEquals("APPROVED", response.status());
        assertEquals(555L, response.resultingTransactionId());
        assertEquals(NOW, response.resolvedAt());
    }

    // sendMoney's own idempotency-key lookup can hand back an existing, not-COMPLETED transaction (from an
    // earlier failed approve() attempt) instead of retrying - the request must stay PENDING in that case, not
    // be marked APPROVED against a payment that didn't actually go through.
    @Test
    void approve_sendMoneyReturnsANonCompletedTransaction_leavesTheRequestPending() {
        MoneyRequest pending = stored(1, REQUESTER, PAYER, MoneyRequestStatus.PENDING);
        when(requests.findById(1L)).thenReturn(Optional.of(pending));
        Transaction stillPending = new Transaction();
        stillPending.setTransactionId(555);
        stillPending.setStatus(TransactionStatus.NEEDS_RECONCILIATION);
        when(phonepeService.sendMoney(PAYER, REQUESTER, AMOUNT, null, "money-request-1")).thenReturn(stillPending);

        assertThrows(TransferFailedException.class, () -> service.approve(PAYER, 1));

        verify(requests, never()).save(any());
        assertEquals(MoneyRequestStatus.PENDING, pending.getStatus());
    }

    @Test
    void approve_notTheRequestsPayer_throwsNotFound() {
        MoneyRequest pending = stored(1, REQUESTER, PAYER, MoneyRequestStatus.PENDING);
        when(requests.findById(1L)).thenReturn(Optional.of(pending));

        assertThrows(MoneyRequestNotFoundException.class, () -> service.approve(9000000001L, 1));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void approve_alreadyResolved_throwsInvalidRequest_withoutMovingMoneyAgain() {
        MoneyRequest approved = stored(1, REQUESTER, PAYER, MoneyRequestStatus.APPROVED);
        when(requests.findById(1L)).thenReturn(Optional.of(approved));

        assertThrows(InvalidRequestException.class, () -> service.approve(PAYER, 1));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void approve_doesNotExist_throwsNotFound() {
        when(requests.findById(1L)).thenReturn(Optional.empty());

        assertThrows(MoneyRequestNotFoundException.class, () -> service.approve(PAYER, 1));
    }

    // ---------- decline ----------

    @Test
    void decline_marksItDeclined_withoutMovingAnyMoney() {
        MoneyRequest pending = stored(1, REQUESTER, PAYER, MoneyRequestStatus.PENDING);
        when(requests.findById(1L)).thenReturn(Optional.of(pending));
        when(requests.save(any(MoneyRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        MoneyRequestResponse response = service.decline(PAYER, 1);

        assertEquals("DECLINED", response.status());
        assertEquals(NOW, response.resolvedAt());
        verifyNoInteractions(phonepeService);
    }

    @Test
    void decline_notTheRequestsPayer_throwsNotFound() {
        MoneyRequest pending = stored(1, REQUESTER, PAYER, MoneyRequestStatus.PENDING);
        when(requests.findById(1L)).thenReturn(Optional.of(pending));

        assertThrows(MoneyRequestNotFoundException.class, () -> service.decline(REQUESTER, 1));
    }

    @Test
    void decline_alreadyResolved_throwsInvalidRequest() {
        MoneyRequest declined = stored(1, REQUESTER, PAYER, MoneyRequestStatus.DECLINED);
        when(requests.findById(1L)).thenReturn(Optional.of(declined));

        assertThrows(InvalidRequestException.class, () -> service.decline(PAYER, 1));
    }

    // ---------- list ----------

    @Test
    void listFor_returnsRequestsFromEitherSide_withTheCorrectDirection() {
        when(requests.findByRequesterPhnoOrPayerPhnoOrderByIdDesc(REQUESTER, REQUESTER)).thenReturn(List.of(
                stored(2, REQUESTER, PAYER, MoneyRequestStatus.PENDING),      // I'm asking
                stored(1, PAYER, REQUESTER, MoneyRequestStatus.PENDING)));    // someone's asking me

        List<MoneyRequestResponse> list = service.listFor(REQUESTER);

        assertEquals("OUTGOING", list.get(0).direction());
        assertEquals("INCOMING", list.get(1).direction());
    }
}
