package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.BankLoginResult;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.LoginResponse;
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
import java.util.List;

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

    public Transaction sendMoney(long payer, long receiver, BigDecimal amount, String note) {
        requireValidPhone(receiver);
        BigDecimal value = requireValidAmount(amount);
        if (payer == receiver) {
            throw new InvalidRequestException("You cannot send money to yourself");
        }

        // No pre-flight check on the receiver here: the bank's transfer is atomic, so if the receiver does not
        // exist the whole call fails with nothing moved - a separate lookup first would only add a network
        // round trip without adding any safety.
        Transaction t = record(payer, receiver, value, "Transfer", note);
        transfer(t, payer, receiver, value);
        return settle(t, TransactionStatus.COMPLETED, null);
    }

    public Transaction makePayment(long payer, BigDecimal amount, String note) {
        BigDecimal value = requireValidAmount(amount);
        Transaction t = record(payer, null, value, "Payment", note);
        debit(t, payer, value);
        return settle(t, TransactionStatus.COMPLETED, null);
    }

    // ---------- history ----------

    /** Everything this person paid, plus completed payments they received. Newest first. from/to are optional. */
    public PageResponse<Transaction> transactionsOf(long viewer, int page, int size, Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("'from' must not be after 'to'.");
        }
        return PageResponse.of(transactions.findVisibleTo(viewer, from, to, pageable(page, size)));
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
            } catch (BankOutcomeUnknownException e) {
                if (attempt >= MAX_TRANSFER_ATTEMPTS) {
                    unresolved(t, "Transfer not confirmed after " + attempt + " attempts: " + e.getMessage());
                    throw new TransferFailedException("We could not confirm your payment with the bank. Check your balance "
                            + "and transactions before trying again. Reference: " + t.getTransactionId());
                }
                // Retrying with the SAME idempotency key is always safe here too: the bank either already
                // completed this exact attempt and returns that result unchanged, or it never did and executes
                // it fresh - either way the money can never move twice.
            } catch (RuntimeException e) {
                // refused (bad receiver, insufficient funds) or never reached the bank: nothing moved either way
                settle(t, TransactionStatus.FAILED, e.getMessage());
                throw e;
            }
        }
    }

    // ---------- bookkeeping ----------

    private Transaction record(long payer, Long receiver, BigDecimal amount, String mode, String note) {
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
            try {
                return transactions.saveAndFlush(t);
            } catch (DataIntegrityViolationException e) {
                // another request took the same number a moment ago; the unique constraint caught it, so take the next one
                if (attempt >= MAX_ID_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private long nextTransactionId() {
        Long highest = transactions.findMaxTransactionId();
        return highest == null ? FIRST_TRANSACTION_ID : highest + 1;
    }

    private Transaction settle(Transaction t, TransactionStatus status, String reason) {
        t.setStatus(status);
        t.setFailureReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 250)));
        try {
            return transactions.save(t);
        } catch (RuntimeException e) {
            // The money has already moved; failing the request now would only hide that. Leave a loud trace instead.
            log.error("Could not save final status {} for transaction {}: {}", status, t.getTransactionId(), e.getMessage());
            return t;
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

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    // keep phone numbers out of the logs
    private static String mask(long phno) {
        String s = String.valueOf(phno);
        return "XXXXXX" + s.substring(Math.max(0, s.length() - 4));
    }
}
