package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.BankLoginResult;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.LoginResponse;
import com.example.phonepayservice.dto.MonthlySummaryResponse;
import com.example.phonepayservice.dto.PageResponse;
import com.example.phonepayservice.dto.ProfileResponse;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.BankConflictException;
import com.example.phonepayservice.exception.BankOutcomeUnknownException;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.TransactionNotFoundException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Moving money touches two systems (this database and the bank), and no database transaction can span both, so this
 * class is deliberately NOT @Transactional. Instead every payment is written down as PENDING first, then the bank is
 * called, then the result is written down. A crash half way therefore always leaves a visible PENDING row.
 */
@Service
public class PhonepeService {
    private static final Logger log = LoggerFactory.getLogger(PhonepeService.class);

    static final long FIRST_TRANSACTION_ID = 100000;
    private static final int MAX_ID_ATTEMPTS = 5;
    private static final int MAX_TRANSFER_ATTEMPTS = 3;
    private static final int MAX_SETTLE_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 120;
    private static final long BACKOFF_JITTER_MS = 80;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final BankGateway bank;
    private final SessionService sessions;
    private final TransactionRepository transactions;
    private final Clock clock;

    public PhonepeService(BankGateway bank, SessionService sessions, TransactionRepository transactions, Clock clock) {
        this.bank = bank;
        this.sessions = sessions;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---------- login ----------

    public LoginResponse login(long phno, String pin) {
        requireValidPhone(phno);
        // The bank IS the credential check: it verifies the PIN and tells us the account's real name.
        BankLoginResult verified = bank.login(phno, pin);
        SessionService.IssuedSession session = sessions.start(phno);
        return new LoginResponse(session.token(), session.expiresAt(), phno, verified.getName());
    }

    public void logout(String token) {
        sessions.end(token);
    }

    // ---------- account ----------

    public ProfileResponse profile(long phno) {
        BankUser user = bank.findUser(phno);
        return new ProfileResponse(phno, user.getName(), user.getAcno(), money(user.getBalance()));
    }

    public BalanceResponse balance(long phno) {
        return new BalanceResponse(phno, money(bank.findUser(phno).getBalance()));
    }

    // ---------- payments ----------

    public Transaction sendMoney(long payer, long receiver, BigDecimal amount, String note, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Transaction existing = transactions.findByPhnoAndIdempotencyKey(payer, key).orElse(null);
            if (existing != null) {
                return matchingExistingOrThrow(existing, receiver, amount);
            }
        }
        requireValidPhone(receiver);
        BigDecimal value = requireValidAmount(amount);
        if (payer == receiver) {
            throw new InvalidRequestException("You cannot send money to yourself");
        }

        // No pre-flight check on the receiver here: the bank's transfer is atomic, so if the receiver does not
        // exist the whole call fails with nothing moved - a separate lookup first would only add a network
        // round trip without adding any safety.
        RecordOutcome outcome = record(payer, receiver, value, "Transfer", note, key);
        if (!outcome.isNew()) {
            // lost a race with a concurrent request carrying the same key; that request owns this payment
            return matchingExistingOrThrow(outcome.transaction(), receiver, value);
        }
        Transaction t = outcome.transaction();
        transfer(t, payer, receiver, value);
        return settle(t, TransactionStatus.COMPLETED, null);
    }

    public Transaction makePayment(long payer, BigDecimal amount, String note, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Transaction existing = transactions.findByPhnoAndIdempotencyKey(payer, key).orElse(null);
            if (existing != null) {
                return matchingExistingOrThrow(existing, null, amount);
            }
        }
        BigDecimal value = requireValidAmount(amount);
        RecordOutcome outcome = record(payer, null, value, "Payment", note, key);
        if (!outcome.isNew()) {
            return matchingExistingOrThrow(outcome.transaction(), null, value);
        }
        Transaction t = outcome.transaction();
        debit(t, payer, value);
        return settle(t, TransactionStatus.COMPLETED, null);
    }

    // ---------- history ----------

    /**
     * Everything this person paid, plus completed payments they received. Newest first. from/to/counterparty/
     * noteContains are all optional.
     */
    public PageResponse<Transaction> transactionsOf(long viewer, int page, int size, Instant from, Instant to,
                                                      Long counterparty, String noteContains) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("'from' must not be after 'to'.");
        }
        return PageResponse.of(transactions.findVisibleTo(viewer, from, to, counterparty, noteContains, pageable(page, size)));
    }

    // month defaults to the current one (in UTC) when not given, so GET /phonepe/summary with no query string
    // is always a meaningful answer, not an error.
    public MonthlySummaryResponse monthlySummary(long viewer, String month) {
        YearMonth ym = (month == null || month.isBlank()) ? YearMonth.now(clock) : parseMonth(month);
        Instant from = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = ym.atEndOfMonth().atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant();

        return new MonthlySummaryResponse(ym.toString(),
                money(transactions.sumSent(viewer, from, to)), transactions.countSent(viewer, from, to),
                money(transactions.sumReceived(viewer, from, to)), transactions.countReceived(viewer, from, to));
    }

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("month must be in YYYY-MM format.");
        }
    }

    // page/size come straight from a query parameter, so out-of-range values are a caller mistake, not a crash.
    private Pageable pageable(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be 0 or more, and size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        return PageRequest.of(page, size, Sort.by("id").descending());
    }

    /** A transaction is only ever shown to the people in it; anyone else gets "not found". */
    public Transaction transaction(long viewer, long transactionId) {
        return transactions.findByTransactionId(transactionId)
                .filter(t -> t.getPhno() == viewer || (t.getReceiverPhno() != null && t.getReceiverPhno() == viewer))
                .filter(t -> visibleTo(t, viewer))
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found"));
    }

    // ---------- money movement ----------

    private void debit(Transaction t, long payer, BigDecimal amount) {
        try {
            bank.withdraw(payer, amount);
        } catch (BankOutcomeUnknownException e) {
            // The withdrawal may or may not have happened. Refunding or retrying could create or destroy money.
            unresolved(t, "Withdrawal not confirmed by the bank: " + e.getMessage());
            throw new TransferFailedException("We could not confirm your payment with the bank. Check your balance and "
                    + "transactions before trying again. Reference: " + t.getTransactionId());
        } catch (RuntimeException e) {
            settle(t, TransactionStatus.FAILED, e.getMessage());   // the bank refused or was unreachable; nothing moved
            throw e;
        }
    }

    // The bank's transfer moves both legs in one database transaction, so unlike the old separate
    // withdraw-then-deposit design there is no window where only one side has happened, and no refund logic is
    // needed: either it goes through completely, or the bank guarantees nothing changed.
    private void transfer(Transaction t, long payer, long receiver, BigDecimal amount) {
        String idempotencyKey = "phonepe-" + t.getTransactionId();
        for (int attempt = 1; ; attempt++) {
            try {
                bank.transfer(payer, receiver, amount, idempotencyKey);
                return;
            } catch (BankConflictException e) {
                // A conflict means the bank's transaction rolled back completely - nothing committed on this
                // attempt, so exhausting retries is a plain (if unusual) failure, not an unknown outcome.
                if (attempt >= MAX_TRANSFER_ATTEMPTS) {
                    settle(t, TransactionStatus.FAILED, "The bank stayed busy after " + attempt + " attempts: " + e.getMessage());
                    throw new TransferFailedException("The bank was too busy to complete your transfer. Please try again.");
                }
                // retrying with the SAME idempotency key is always safe: nothing committed on the failed attempt
                backoffBeforeRetry(attempt);
            } catch (BankOutcomeUnknownException e) {
                if (attempt >= MAX_TRANSFER_ATTEMPTS) {
                    unresolved(t, "Transfer not confirmed after " + attempt + " attempts: " + e.getMessage());
                    throw new TransferFailedException("We could not confirm your payment with the bank. Check your balance "
                            + "and transactions before trying again. Reference: " + t.getTransactionId());
                }
                // Retrying with the SAME idempotency key is always safe here too: the bank either already
                // completed this exact attempt and returns that result unchanged, or it never did and executes
                // it fresh - either way the money can never move twice.
                backoffBeforeRetry(attempt);
            } catch (RuntimeException e) {
                // refused (bad receiver, insufficient funds) or never reached the bank: nothing moved either way
                settle(t, TransactionStatus.FAILED, e.getMessage());
                throw e;
            }
        }
    }

    // A short, jittered pause before a retry, so a struggling bank isn't hit with the next attempt at the exact
    // moment it's least able to handle it. Jitter spreads out multiple concurrent callers who would otherwise
    // all retry at the same fixed interval and re-collide.
    private void backoffBeforeRetry(int attempt) {
        long delay = BACKOFF_BASE_MS * attempt + ThreadLocalRandom.current().nextLong(BACKOFF_JITTER_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- bookkeeping ----------

    // isNew tells the caller whether this is a fresh row that still needs the bank called (true), or an
    // existing row a concurrent identical request already recorded a moment ago (false) - in the latter case
    // the caller must return it as-is and must NOT call the bank again.
    private record RecordOutcome(Transaction transaction, boolean isNew) {}

    private RecordOutcome record(long payer, Long receiver, BigDecimal amount, String mode, String note, String idempotencyKey) {
        for (int attempt = 1; ; attempt++) {
            Transaction t = new Transaction();
            t.setTransactionId(nextTransactionId());
            t.setPhno(payer);
            t.setReceiverPhno(receiver);
            t.setAmount(amount);
            t.setMode(mode);
            t.setStatus(TransactionStatus.PENDING);
            t.setCreatedAt(clock.instant());
            t.setNote(note == null || note.isBlank() ? null : note.trim());
            t.setIdempotencyKey(idempotencyKey);
            try {
                return new RecordOutcome(transactions.saveAndFlush(t), true);
            } catch (DataIntegrityViolationException e) {
                // Two different constraints can cause this: the transactionId collided with a concurrent
                // insert (take the next number and retry), or - only possible when a key was given - a
                // concurrent request with the SAME idempotency key won the race (return its row, don't retry).
                if (idempotencyKey != null) {
                    Transaction existing = transactions.findByPhnoAndIdempotencyKey(payer, idempotencyKey).orElse(null);
                    if (existing != null) {
                        return new RecordOutcome(existing, false);
                    }
                }
                if (attempt >= MAX_ID_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static String normalizeKey(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
    }

    // A retry must carry the SAME request, not just the same key - otherwise a reused key (a client bug, or a
    // copy-pasted key) would silently return success for a payment that was never actually asked for.
    private Transaction matchingExistingOrThrow(Transaction existing, Long expectedReceiver, BigDecimal expectedAmount) {
        boolean receiverMatches = Objects.equals(existing.getReceiverPhno(), expectedReceiver);
        if (!receiverMatches || existing.getAmount().compareTo(expectedAmount) != 0) {
            throw new InvalidRequestException("This idempotency key was already used for a different request.");
        }
        return existing;
    }

    private long nextTransactionId() {
        Long highest = transactions.findMaxTransactionId();
        return highest == null ? FIRST_TRANSACTION_ID : highest + 1;
    }

    // A few retries absorb a transient DB blip (a connection drop, a momentary pool exhaustion) - the common
    // case this is actually likely to hit. If it still won't save after that, COMPLETED and NEEDS_RECONCILIATION
    // both mean money may have already moved: returning the in-memory object as if this succeeded would tell the
    // caller (and, for COMPLETED, the receiver's own transaction list) that it did, when the database itself
    // does not reflect that at all. Throwing here instead means the caller sees the same honest "check your
    // balance" uncertainty they'd get from an outcome the bank itself left unconfirmed.
    private Transaction settle(Transaction t, TransactionStatus status, String reason) {
        t.setStatus(status);
        t.setFailureReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 250)));
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.save(t);
            } catch (RuntimeException e) {
                log.error("Could not save final status {} for transaction {} (attempt {}/{}): {}",
                        status, t.getTransactionId(), attempt, MAX_SETTLE_ATTEMPTS, e.getMessage());
                if (attempt >= MAX_SETTLE_ATTEMPTS) {
                    if (status == TransactionStatus.COMPLETED || status == TransactionStatus.NEEDS_RECONCILIATION) {
                        throw new TransferFailedException("Your payment may have gone through, but we could not "
                                + "record its final status. Check your balance and transactions before trying "
                                + "again. Reference: " + t.getTransactionId());
                    }
                    // FAILED: nothing moved, and the caller is about to see the real reason why from its own
                    // exception - leaving this row stuck PENDING is a bookkeeping loose end, not a false success.
                    return t;
                }
            }
        }
    }

    private void unresolved(Transaction t, String reason) {
        log.error("Transaction {} needs manual reconciliation: payer {}, receiver {}, amount {}. {}",
                t.getTransactionId(), mask(t.getPhno()), t.getReceiverPhno() == null ? "-" : mask(t.getReceiverPhno()),
                t.getAmount(), reason);
        settle(t, TransactionStatus.NEEDS_RECONCILIATION, reason);
    }

    // ---------- helpers ----------

    // Payers always see their own rows; receivers only see payments that really reached them.
    private boolean visibleTo(Transaction t, long viewer) {
        return t.getPhno() == viewer || t.getStatus() == null || t.getStatus() == TransactionStatus.COMPLETED;
    }

    private static void requireValidPhone(long phno) {
        if (phno < 6000000000L || phno > 9999999999L) {
            throw new InvalidRequestException("Invalid mobile number");
        }
    }

    private static BigDecimal requireValidAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidRequestException("Amount too low");
        }
        if (amount.scale() > 2) {
            throw new InvalidRequestException("Amount can have at most 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // keep phone numbers out of the logs
    private static String mask(long phno) {
        String s = String.valueOf(phno);
        return "XXXXXX" + s.substring(Math.max(0, s.length() - 4));
    }
}
