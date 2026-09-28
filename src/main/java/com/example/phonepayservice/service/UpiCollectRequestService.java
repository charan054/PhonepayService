package com.example.phonepayservice.service;

import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.entity.UpiCollectRequest;
import com.example.phonepayservice.entity.UpiCollectRequestStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.exception.UpiCollectRequestNotFoundException;
import com.example.phonepayservice.repository.UpiCollectRequestRepository;
import com.example.phonepayservice.util.UpiId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * A merchant's request to collect payment from a buyer by their UPI ID, approved or declined only by the buyer
 * themselves - the same lifecycle MoneyRequestService already runs for a peer-to-peer ask, except creation is
 * gated by the internal service key (see ServiceKeyInterceptor) rather than a Bearer session, since the merchant
 * calling create() is a trusted backend (e.g. OrderService), not a logged-in PhonepayService user. Approving one
 * moves money by calling straight back into PhonepeService.makePayment() - the exact same call the storefront's
 * synchronous PhonePe checkout already makes, just triggered later, from the buyer's own approval instead of
 * from checkout itself.
 * <p>
 * This system has no scheduler (same constraint the rest of this codebase already works under - see
 * OrderService's loyalty-points-expiry design), so a PENDING request past its expiresAt isn't swept by a
 * background job. It's checked lazily, the moment anything next reads that request - a still-open request a
 * nobody ever looks at again would otherwise just sit there forever, which is harmless (a payment that never
 * happened, same as CANCELLED cleanup) but is corrected as soon as any caller does look.
 */
@Service
public class UpiCollectRequestService {
    private final UpiCollectRequestRepository requests;
    private final PhonepeService phonepeService;
    private final Clock clock;
    private final Duration timeout;

    public UpiCollectRequestService(UpiCollectRequestRepository requests, PhonepeService phonepeService, Clock clock,
                                     @Value("${phonepe.upi.collect-request.timeout-minutes:4}") long timeoutMinutes) {
        this.requests = requests;
        this.phonepeService = phonepeService;
        this.clock = clock;
        this.timeout = Duration.ofMinutes(timeoutMinutes);
    }

    // Idempotent on merchantReference: a retried create() call (the caller never got the first response, a
    // network blip) returns the SAME request rather than raising a second collect ask for the same order.
    public UpiCollectRequest create(String merchantReference, String upiId, BigDecimal amount, String note) {
        UpiCollectRequest existing = requests.findByMerchantReference(merchantReference).orElse(null);
        if (existing != null) {
            return existing;
        }

        long payerPhno = UpiId.toPhno(upiId);
        PhonepeService.requireValidPhone(payerPhno);
        BigDecimal value = PhonepeService.requireValidAmount(amount);

        UpiCollectRequest r = new UpiCollectRequest();
        r.setMerchantReference(merchantReference);
        r.setPayerPhno(payerPhno);
        r.setAmount(value);
        r.setNote(note);
        r.setStatus(UpiCollectRequestStatus.PENDING);
        r.setCreatedAt(clock.instant());
        r.setExpiresAt(clock.instant().plus(timeout));
        return requests.save(r);
    }

    public UpiCollectRequest getByMerchantReference(String merchantReference) {
        UpiCollectRequest r = requests.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new UpiCollectRequestNotFoundException("UPI collect request not found"));
        return expireIfDue(r);
    }

    /** Everything still PENDING for this payer (after lazily expiring anything past its deadline), newest first. */
    public List<UpiCollectRequest> listPendingFor(long payerPhno) {
        return requests.findByPayerPhnoAndStatusOrderByIdDesc(payerPhno, UpiCollectRequestStatus.PENDING).stream()
                .map(this::expireIfDue)
                .filter(r -> r.getStatus() == UpiCollectRequestStatus.PENDING)
                .toList();
    }

    /**
     * Same "only mark APPROVED once the transaction actually completed" reasoning as
     * MoneyRequestService.approve(): "upi-collect-&lt;id&gt;" as the idempotency key means a retried approve()
     * can never charge twice, but a genuine failure (insufficient funds) leaves the request PENDING so the buyer
     * can top up and try again before it expires.
     */
    public UpiCollectRequest approve(long payerPhno, long requestId) {
        UpiCollectRequest r = findOwnedByPayer(payerPhno, requestId);
        r = expireIfDue(r);
        requirePending(r);

        Transaction t = phonepeService.makePayment(payerPhno, r.getAmount(),
                "UPI collect: " + (r.getNote() == null ? "Order payment" : r.getNote()), "upi-collect-" + r.getId());
        if (t.getStatus() != TransactionStatus.COMPLETED) {
            throw new TransferFailedException("This payment did not complete. Please try approving it again.");
        }

        r.setStatus(UpiCollectRequestStatus.APPROVED);
        r.setResolvedAt(clock.instant());
        r.setResultTransactionId(t.getTransactionId());
        return requests.save(r);
    }

    public UpiCollectRequest decline(long payerPhno, long requestId) {
        UpiCollectRequest r = findOwnedByPayer(payerPhno, requestId);
        r = expireIfDue(r);
        requirePending(r);

        r.setStatus(UpiCollectRequestStatus.DECLINED);
        r.setResolvedAt(clock.instant());
        return requests.save(r);
    }

    private UpiCollectRequest expireIfDue(UpiCollectRequest r) {
        if (r.getStatus() == UpiCollectRequestStatus.PENDING && !clock.instant().isBefore(r.getExpiresAt())) {
            r.setStatus(UpiCollectRequestStatus.EXPIRED);
            r.setResolvedAt(clock.instant());
            return requests.save(r);
        }
        return r;
    }

    // Only the payer can approve/decline - never a stranger, same "not found" masking MoneyRequestService uses.
    private UpiCollectRequest findOwnedByPayer(long payerPhno, long requestId) {
        return requests.findById(requestId)
                .filter(r -> r.getPayerPhno() == payerPhno)
                .orElseThrow(() -> new UpiCollectRequestNotFoundException("UPI collect request not found"));
    }

    private void requirePending(UpiCollectRequest r) {
        if (r.getStatus() != UpiCollectRequestStatus.PENDING) {
            throw new InvalidRequestException("This request has already been resolved");
        }
    }
}
