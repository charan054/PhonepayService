package com.example.phonepayservice.service;

import com.example.phonepayservice.dto.DisputeDtos.DisputeResponse;
import com.example.phonepayservice.entity.PaymentDispute;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.DisputeNotFoundException;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.repository.PaymentDisputeRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Customer complaints about their own payments. Like PhonepeService this is deliberately not @Transactional: an
 * approval calls the bank (through merchantRefund) and no database transaction can span both. Money safety comes from
 * the refund's own idempotency key, "dispute-<id>": if the approval is retried after the refund went through but
 * before the dispute was marked approved, the retry gets the same refund back instead of paying twice.
 */
@Service
public class DisputeService {
    static final Duration DISPUTE_WINDOW = Duration.ofDays(30);

    private final PaymentDisputeRepository disputes;
    private final PhonepeService phonepe;
    private final Clock clock;

    public DisputeService(PaymentDisputeRepository disputes, PhonepeService phonepe, Clock clock) {
        this.disputes = disputes;
        this.phonepe = phonepe;
        this.clock = clock;
    }

    /** Only the payer of a completed makepayment, within 30 days, once per payment. */
    public DisputeResponse raise(long caller, long transactionId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("Tell us what went wrong with this payment");
        }
        Transaction t = phonepe.transaction(caller, transactionId);   // 404 for someone else's or an unknown payment
        if (t.getPhno() != caller || !"Payment".equals(t.getMode())) {
            throw new InvalidRequestException("Only a payment you made can be disputed");
        }
        if (t.getStatus() != TransactionStatus.COMPLETED) {
            throw new InvalidRequestException("Only a completed payment can be disputed");
        }
        if (t.getCreatedAt() != null && t.getCreatedAt().plus(DISPUTE_WINDOW).isBefore(clock.instant())) {
            throw new InvalidRequestException("Payments can only be disputed within 30 days");
        }
        if (disputes.findByTransactionId(transactionId).isPresent()) {
            throw new InvalidRequestException("This payment already has a dispute");
        }
        PaymentDispute d = new PaymentDispute();
        d.setTransactionId(transactionId);
        d.setPhno(caller);
        d.setReason(reason.trim());
        d.setCreatedAt(clock.instant());
        try {
            return DisputeResponse.from(disputes.save(d));
        } catch (DataIntegrityViolationException e) {
            // lost a race with a second click: the unique constraint is what actually enforces one per payment
            throw new InvalidRequestException("This payment already has a dispute");
        }
    }

    public List<DisputeResponse> mine(long caller) {
        return disputes.findByPhnoOrderByIdDesc(caller).stream().map(DisputeResponse::from).toList();
    }

    public List<DisputeResponse> openQueue() {
        return disputes.findByStatusOrderByIdAsc(PaymentDispute.OPEN).stream().map(DisputeResponse::from).toList();
    }

    /** Staff decision. A failed refund leaves the dispute OPEN so it can simply be approved again. */
    public DisputeResponse resolve(long disputeId, boolean approve, String note) {
        PaymentDispute d = disputes.findById(disputeId)
                .orElseThrow(() -> new DisputeNotFoundException("Dispute not found"));
        if (!PaymentDispute.OPEN.equals(d.getStatus())) {
            throw new InvalidRequestException("This dispute has already been " + d.getStatus().toLowerCase());
        }
        if (approve) {
            Transaction refund = phonepe.merchantRefund(d.getTransactionId(), null, "dispute-" + d.getId());
            d.setRefundTransactionId(refund.getTransactionId());
            d.setStatus(PaymentDispute.APPROVED);
        } else {
            d.setStatus(PaymentDispute.REJECTED);
        }
        d.setResolutionNote(note == null || note.isBlank() ? null : note.trim());
        d.setResolvedAt(clock.instant());
        try {
            return DisputeResponse.from(disputes.save(d));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new InvalidRequestException("This dispute was just resolved by someone else");
        }
    }
}
