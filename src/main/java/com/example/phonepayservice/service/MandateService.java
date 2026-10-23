package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.MandateDtos;
import com.example.phonepayservice.entity.PaymentMandate;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.MandateNotFoundException;
import com.example.phonepayservice.repository.PaymentMandateRepository;
import com.example.phonepayservice.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Mandates: a customer lets the shop charge their PhonePe account for subscription orders without being asked each time,
 * inside limits they choose (per charge, per month, until a date) and can revoke at once. A charge is an ordinary
 * makePayment, so it appears in the customer's history and can be refunded like any other.
 *
 * Not @Transactional as a whole, for the same reason PhonepeService never wraps a bank call in a database transaction:
 * each repository write is its own atomic step. The monthly total is reserved BEFORE the bank is asked and given back if the
 * payment fails; an optimistic version stops two simultaneous charges both fitting under the same remaining allowance.
 */
@Service
public class MandateService {
    private static final Logger log = LoggerFactory.getLogger(MandateService.class);
    private static final String MERCHANT = "Charan Mart";
    private static final int RESERVE_RETRIES = 5;

    private final PaymentMandateRepository mandates;
    private final TransactionRepository transactions;
    private final PhonepeService phonepeService;
    private final BankGateway bank;
    private final Clock clock;
    private final ZoneId zone;
    private final BigDecimal maxPerCharge;
    private final BigDecimal maxMonthly;
    private final int maxActive;

    public MandateService(PaymentMandateRepository mandates, TransactionRepository transactions, PhonepeService phonepeService,
                          BankGateway bank, Clock clock,
                          @Value("${phonepe.mandate.zone:Asia/Kolkata}") String zone,
                          @Value("${phonepe.mandate.max-per-charge:50000}") BigDecimal maxPerCharge,
                          @Value("${phonepe.mandate.max-monthly:200000}") BigDecimal maxMonthly,
                          @Value("${phonepe.mandate.max-active-per-customer:3}") int maxActive) {
        this.mandates = mandates;
        this.transactions = transactions;
        this.phonepeService = phonepeService;
        this.bank = bank;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.maxPerCharge = maxPerCharge;
        this.maxMonthly = maxMonthly;
        this.maxActive = maxActive;
    }

    private String thisMonth() {
        return YearMonth.now(clock.withZone(zone)).toString();
    }

    private BigDecimal usedThisMonth(PaymentMandate m) {
        return thisMonth().equals(m.getChargeMonth()) && m.getChargedThisMonth() != null ? m.getChargedThisMonth() : BigDecimal.ZERO;
    }

    private MandateDtos.View view(PaymentMandate m) {
        return MandateDtos.View.of(m, usedThisMonth(m));
    }

    /** The customer's PIN is checked again with the bank (this is permission to take money later) before anything is stored. */
    public MandateDtos.View create(long payer, MandateDtos.CreateRequest request) {
        if (request.perChargeLimit().compareTo(maxPerCharge) > 0) {
            throw new InvalidRequestException("The most for one charge can be at most " + maxPerCharge.toPlainString());
        }
        if (request.monthlyLimit().compareTo(maxMonthly) > 0) {
            throw new InvalidRequestException("The most per month can be at most " + maxMonthly.toPlainString());
        }
        if (request.perChargeLimit().compareTo(request.monthlyLimit()) > 0) {
            throw new InvalidRequestException("The most for one charge cannot be more than the most per month");
        }
        if (mandates.countByPayerPhnoAndStatus(payer, PaymentMandate.ACTIVE) >= maxActive) {
            throw new InvalidRequestException("You already have " + maxActive + " active mandates - revoke one first");
        }
        bank.login(payer, request.pin());     // throws when the PIN is wrong (and counts toward the bank's lockout)

        Instant now = clock.instant();
        PaymentMandate m = new PaymentMandate();
        m.setPayerPhno(payer);
        m.setMerchant(MERCHANT);
        m.setPerChargeLimit(request.perChargeLimit());
        m.setMonthlyLimit(request.monthlyLimit());
        m.setChargedThisMonth(BigDecimal.ZERO);
        m.setChargeMonth(thisMonth());
        m.setValidUntil(now.plus(request.validDays(), ChronoUnit.DAYS));
        m.setStatus(PaymentMandate.ACTIVE);
        m.setCreatedAt(now);
        return view(mandates.save(m));
    }

    public List<MandateDtos.View> listFor(long payer) {
        return mandates.findByPayerPhnoOrderByIdDesc(payer).stream().map(this::view).toList();
    }

    /** Revoking is immediate and final: the next charge is refused. Revoking twice is fine. */
    public MandateDtos.View revoke(long payer, long id) {
        PaymentMandate m = mandates.findById(id).filter(x -> x.getPayerPhno() == payer)
                .orElseThrow(() -> new MandateNotFoundException("Mandate not found"));
        if (PaymentMandate.ACTIVE.equals(m.getStatus())) {
            m.setStatus(PaymentMandate.REVOKED);
            m.setRevokedAt(clock.instant());
            m = mandates.save(m);
        }
        return view(m);
    }

    /** For the shop (service key): is this mandate real, whose is it, and what is left - used before accepting it on a subscription. */
    public MandateDtos.View lookup(long id) {
        return view(mandates.findById(id).orElseThrow(() -> new MandateNotFoundException("Mandate not found")));
    }

    /**
     * The shop charges a mandate. Refused (nothing debited) when the mandate is revoked or ended, the amount is over the
     * per-charge limit, or it would take the month past the monthly limit. A retry with the same idempotency key returns the
     * first charge's payment and does not count against the limits a second time.
     */
    public Transaction charge(long id, MandateDtos.ChargeRequest request) {
        PaymentMandate first = mandates.findById(id).orElseThrow(() -> new MandateNotFoundException("Mandate not found"));
        String key = request.idempotencyKey().trim();
        Transaction already = transactions.findByPhnoAndIdempotencyKey(first.getPayerPhno(), key).orElse(null);
        if (already != null) {
            // same payment as before (PhonepeService checks the amount matches); counted against the limits the first time
            return phonepeService.makePayment(first.getPayerPhno(), request.amount(), request.note(), key);
        }

        PaymentMandate reserved = reserve(id, request.amount());
        try {
            Transaction payment = phonepeService.makePayment(reserved.getPayerPhno(), request.amount(),
                    request.note() == null || request.note().isBlank() ? "Subscription (mandate #" + id + ")" : request.note(), key);
            if (payment.getStatus() == TransactionStatus.FAILED) {
                release(id, request.amount());
            }
            return payment;
        } catch (RuntimeException e) {
            // A clean refusal moved no money, so give the allowance back. (An exception that leaves the outcome unknown
            // keeps it reserved - phonepeService records those as needing reconciliation, and over-counting is the safe side.)
            if (e instanceof com.example.phonepayservice.exception.BalanceException
                    || e instanceof InvalidRequestException
                    || e instanceof com.example.phonepayservice.exception.UserNotExistException
                    || e instanceof com.example.phonepayservice.exception.AccountLockedException
                    || e instanceof com.example.phonepayservice.exception.TransferFailedException) {
                release(id, request.amount());
            }
            throw e;
        }
    }

    private PaymentMandate reserve(long id, BigDecimal amount) {
        for (int attempt = 1; ; attempt++) {
            PaymentMandate m = mandates.findById(id).orElseThrow(() -> new MandateNotFoundException("Mandate not found"));
            Instant now = clock.instant();
            if (!PaymentMandate.ACTIVE.equals(m.getStatus())) {
                throw new InvalidRequestException("This mandate has been revoked");
            }
            if (!m.getValidUntil().isAfter(now)) {
                throw new InvalidRequestException("This mandate has ended");
            }
            if (amount.compareTo(m.getPerChargeLimit()) > 0) {
                throw new InvalidRequestException("This is more than the " + m.getPerChargeLimit().toPlainString()
                        + " the customer allowed for one charge");
            }
            BigDecimal used = usedThisMonth(m);
            if (used.add(amount).compareTo(m.getMonthlyLimit()) > 0) {
                throw new InvalidRequestException("This would go over the " + m.getMonthlyLimit().toPlainString()
                        + " the customer allowed per month");
            }
            m.setChargeMonth(thisMonth());
            m.setChargedThisMonth(used.add(amount));
            m.setLastChargeAt(now);
            try {
                return mandates.save(m);
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= RESERVE_RETRIES) {
                    throw e;
                }
            }
        }
    }

    private void release(long id, BigDecimal amount) {
        for (int attempt = 1; attempt <= RESERVE_RETRIES; attempt++) {
            try {
                PaymentMandate m = mandates.findById(id).orElse(null);
                if (m == null) {
                    return;
                }
                BigDecimal used = usedThisMonth(m);
                m.setChargedThisMonth(used.subtract(amount).max(BigDecimal.ZERO));
                mandates.save(m);
                return;
            } catch (OptimisticLockingFailureException e) {
                log.debug("Retrying the release of mandate {} (attempt {})", id, attempt);
            }
        }
        log.warn("Could not give back {} of mandate {}'s monthly allowance; it stays counted", amount, id);
    }
}
