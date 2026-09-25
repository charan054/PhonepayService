package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.RecurringPaymentResponse;
import com.example.phonepayservice.entity.RecurringPayment;
import com.example.phonepayservice.entity.RecurringPaymentStatus;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.RecurringPaymentNotFoundException;
import com.example.phonepayservice.repository.RecurringPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A standing instruction to pay the same payee the same amount every N days, until paused or cancelled. Never
 * @Transactional as a whole (here or in runDuePayments()) for the same reason PhonepeService itself never wraps
 * a bank call in a database transaction - only each individual repository write is its own atomic operation.
 */
@Service
public class RecurringPaymentService {
    private static final Logger log = LoggerFactory.getLogger(RecurringPaymentService.class);

    private final RecurringPaymentRepository recurring;
    private final BankGateway bank;
    private final PhonepeService phonepeService;
    private final Clock clock;

    public RecurringPaymentService(RecurringPaymentRepository recurring, BankGateway bank, PhonepeService phonepeService, Clock clock) {
        this.recurring = recurring;
        this.bank = bank;
        this.phonepeService = phonepeService;
        this.clock = clock;
    }

    // The first run is one interval from now, not immediate - setting one up should never itself move money
    // right away, only starting from the next cycle.
    public RecurringPaymentResponse create(long ownerPhno, long payeePhno, BigDecimal amount, String note, int intervalDays) {
        if (ownerPhno == payeePhno) {
            throw new InvalidRequestException("You cannot set up a recurring payment to yourself");
        }
        bank.findUser(payeePhno);   // throws UserNotExistException if this isn't a real account

        RecurringPayment r = new RecurringPayment();
        r.setOwnerPhno(ownerPhno);
        r.setPayeePhno(payeePhno);
        r.setAmount(amount);
        r.setNote(note);
        r.setIntervalDays(intervalDays);
        r.setStatus(RecurringPaymentStatus.ACTIVE);
        r.setCreatedAt(clock.instant());
        r.setNextRunAt(clock.instant().plus(intervalDays, ChronoUnit.DAYS));
        return RecurringPaymentResponse.from(recurring.save(r));
    }

    public List<RecurringPaymentResponse> listFor(long ownerPhno) {
        return recurring.findByOwnerPhnoOrderByIdDesc(ownerPhno).stream().map(RecurringPaymentResponse::from).toList();
    }

    public RecurringPaymentResponse pause(long ownerPhno, long id) {
        RecurringPayment r = findOwned(ownerPhno, id);
        if (r.getStatus() != RecurringPaymentStatus.ACTIVE) {
            throw new InvalidRequestException("Only an active recurring payment can be paused");
        }
        r.setStatus(RecurringPaymentStatus.PAUSED);
        return RecurringPaymentResponse.from(recurring.save(r));
    }

    // Recomputes nextRunAt from now (not from whenever it was paused), so resuming after a long pause doesn't
    // trigger a burst of "overdue" catch-up payments on the next scheduler tick.
    public RecurringPaymentResponse resume(long ownerPhno, long id) {
        RecurringPayment r = findOwned(ownerPhno, id);
        if (r.getStatus() != RecurringPaymentStatus.PAUSED) {
            throw new InvalidRequestException("Only a paused recurring payment can be resumed");
        }
        r.setStatus(RecurringPaymentStatus.ACTIVE);
        r.setNextRunAt(clock.instant().plus(r.getIntervalDays(), ChronoUnit.DAYS));
        return RecurringPaymentResponse.from(recurring.save(r));
    }

    public RecurringPaymentResponse cancel(long ownerPhno, long id) {
        RecurringPayment r = findOwned(ownerPhno, id);
        if (r.getStatus() == RecurringPaymentStatus.CANCELLED) {
            throw new InvalidRequestException("This recurring payment is already cancelled");
        }
        r.setStatus(RecurringPaymentStatus.CANCELLED);
        return RecurringPaymentResponse.from(recurring.save(r));
    }

    /**
     * Runs every ACTIVE recurring payment whose nextRunAt has arrived. A failed run (insufficient funds, the
     * bank unavailable, ...) pauses that one payment rather than leaving it to retry on every future tick -
     * repeatedly hammering a payment that's already failing isn't safe, and a silent infinite-retry loop isn't
     * either; the owner sees it as PAUSED and resumes it once the underlying problem is fixed.
     */
    @Scheduled(fixedRateString = "${phonepe.recurring.check-interval-minutes:60}", timeUnit = TimeUnit.MINUTES)
    public void runDuePayments() {
        List<RecurringPayment> due = recurring.findByStatusAndNextRunAtLessThanEqual(RecurringPaymentStatus.ACTIVE, clock.instant());
        int ranCount = 0;
        for (RecurringPayment r : due) {
            if (runOne(r)) {
                ranCount++;
            }
        }
        if (ranCount > 0) {
            log.info("Ran {} of {} due recurring payment(s)", ranCount, due.size());
        }
    }

    // true only if the payment actually completed and nextRunAt was advanced.
    private boolean runOne(RecurringPayment r) {
        try {
            // Each due occurrence gets its own idempotency key (the interval end it was scheduled for), so a
            // scheduler tick that runs twice for the same occurrence (a retry, an overlapping run) can never
            // transfer twice - the same protection approve() gets from "money-request-<id>".
            String idempotencyKey = "recurring-" + r.getId() + "-" + r.getNextRunAt();
            Transaction t = phonepeService.sendMoney(r.getOwnerPhno(), r.getPayeePhno(), r.getAmount(), r.getNote(), idempotencyKey);
            if (t.getStatus() != TransactionStatus.COMPLETED) {
                pauseAfterFailure(r, "payment did not complete");
                return false;
            }
            r.setLastRunAt(clock.instant());
            r.setNextRunAt(r.getNextRunAt().plus(r.getIntervalDays(), ChronoUnit.DAYS));
            recurring.save(r);
            return true;
        } catch (RuntimeException e) {
            pauseAfterFailure(r, e.getMessage());
            return false;
        }
    }

    private void pauseAfterFailure(RecurringPayment r, String reason) {
        log.error("Recurring payment {} failed and has been paused: {}", r.getId(), reason);
        r.setStatus(RecurringPaymentStatus.PAUSED);
        recurring.save(r);
    }

    private RecurringPayment findOwned(long ownerPhno, long id) {
        return recurring.findById(id)
                .filter(r -> r.getOwnerPhno() == ownerPhno)
                .orElseThrow(() -> new RecurringPaymentNotFoundException("Recurring payment not found"));
    }
}
