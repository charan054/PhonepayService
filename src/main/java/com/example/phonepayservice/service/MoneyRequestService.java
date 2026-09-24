package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.MoneyRequestResponse;
import com.example.phonepayservice.entity.MoneyRequest;
import com.example.phonepayservice.entity.MoneyRequestStatus;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.MoneyRequestNotFoundException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.repository.MoneyRequestRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

/**
 * One person asking another for money. Deliberately a separate service from PhonepeService (which owns actual
 * money movement) rather than folded into it - creating or resolving a request is its own lifecycle, and
 * approving one only ever moves money by calling back into PhonepeService.sendMoney(), the same path a direct
 * "Send Money" already goes through.
 */
@Service
public class MoneyRequestService {
    private final MoneyRequestRepository requests;
    private final BankGateway bank;
    private final PhonepeService phonepeService;
    private final Clock clock;

    public MoneyRequestService(MoneyRequestRepository requests, BankGateway bank, PhonepeService phonepeService, Clock clock) {
        this.requests = requests;
        this.bank = bank;
        this.phonepeService = phonepeService;
        this.clock = clock;
    }

    public MoneyRequestResponse create(long requesterPhno, long payerPhno, BigDecimal amount, String note) {
        if (requesterPhno == payerPhno) {
            throw new InvalidRequestException("You cannot request money from yourself");
        }
        bank.findUser(payerPhno);   // throws UserNotExistException if this isn't a real account

        MoneyRequest r = new MoneyRequest();
        r.setRequesterPhno(requesterPhno);
        r.setPayerPhno(payerPhno);
        r.setAmount(amount);
        r.setNote(note);
        r.setStatus(MoneyRequestStatus.PENDING);
        r.setCreatedAt(clock.instant());
        return MoneyRequestResponse.from(requests.save(r), requesterPhno);
    }

    /**
     * "money-request-&lt;id&gt;" as the idempotency key means a retried approve() (after a timeout, a double
     * click) can never transfer twice - sendMoney recognizes the same key and returns the same transaction
     * instead of moving money again. But that also means a RETRY after a genuine failure (insufficient funds,
     * say) comes back as that same non-COMPLETED transaction rather than a fresh attempt, so the request is only
     * ever marked APPROVED once the returned transaction actually is COMPLETED - otherwise it stays PENDING,
     * exactly as if this approve() had never been called, so a later approve() (once the underlying problem is
     * fixed) tries again for real.
     */
    public MoneyRequestResponse approve(long payerPhno, long requestId) {
        MoneyRequest r = findOwnedByPayer(payerPhno, requestId);
        requirePending(r);

        Transaction t = phonepeService.sendMoney(payerPhno, r.getRequesterPhno(), r.getAmount(), r.getNote(), "money-request-" + r.getId());
        if (t.getStatus() != TransactionStatus.COMPLETED) {
            throw new TransferFailedException("This request's payment did not complete. Please try approving it again.");
        }

        r.setStatus(MoneyRequestStatus.APPROVED);
        r.setResolvedAt(clock.instant());
        r.setResultingTransactionId(t.getTransactionId());
        return MoneyRequestResponse.from(requests.save(r), payerPhno);
    }

    public MoneyRequestResponse decline(long payerPhno, long requestId) {
        MoneyRequest r = findOwnedByPayer(payerPhno, requestId);
        requirePending(r);

        r.setStatus(MoneyRequestStatus.DECLINED);
        r.setResolvedAt(clock.instant());
        return MoneyRequestResponse.from(requests.save(r), payerPhno);
    }

    /** Everything the viewer is involved in, either as requester or payer, newest first. */
    public List<MoneyRequestResponse> listFor(long viewer) {
        return requests.findByRequesterPhnoOrPayerPhnoOrderByIdDesc(viewer, viewer).stream()
                .map(r -> MoneyRequestResponse.from(r, viewer)).toList();
    }

    // Only the payer can approve/decline - never the requester, and never a stranger. A request that exists but
    // belongs to someone else gets the same "not found" a nonexistent one would, matching how a transaction is
    // hidden from anyone but the two people in it (see PhonepeService.transaction()).
    private MoneyRequest findOwnedByPayer(long payerPhno, long requestId) {
        return requests.findById(requestId)
                .filter(r -> r.getPayerPhno() == payerPhno)
                .orElseThrow(() -> new MoneyRequestNotFoundException("Money request not found"));
    }

    private void requirePending(MoneyRequest r) {
        if (r.getStatus() != MoneyRequestStatus.PENDING) {
            throw new InvalidRequestException("This request has already been resolved");
        }
    }
}
