package com.example.phonepayservice.service;

import com.example.phonepayservice.client.BankGateway;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.dto.LoginResponse;
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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
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
    private static final int MAX_CREDIT_ATTEMPTS = 3;

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

    public LoginResponse login(long phno) {
        requireValidPhone(phno);
        BankUser user = bank.findUser(phno);
        SessionService.IssuedSession session = sessions.start(phno);
        return new LoginResponse(session.token(), session.expiresAt(), phno, user.getName());
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

    public Transaction sendMoney(long payer, long receiver, BigDecimal amount) {
        requireValidPhone(receiver);
        BigDecimal value = requireValidAmount(amount);
        if (payer == receiver) {
            throw new InvalidRequestException("You cannot send money to yourself");
        }
        bank.findUser(receiver);   // make sure the receiver exists BEFORE any money moves

        Transaction t = record(payer, receiver, value, "Transfer");
        debit(t, payer, value);
        credit(t, payer, receiver, value);
        return settle(t, TransactionStatus.COMPLETED, null);
    }

    public Transaction makePayment(long payer, BigDecimal amount) {
        BigDecimal value = requireValidAmount(amount);
        Transaction t = record(payer, null, value, "Payment");
        debit(t, payer, value);
        return settle(t, TransactionStatus.COMPLETED, null);
    }

    // ---------- history ----------

    /** Everything this person paid, plus completed payments they received. Newest first. */
    public List<Transaction> transactionsOf(long viewer) {
        return transactions.findByPhnoOrReceiverPhnoOrderByIdDesc(viewer, viewer).stream()
                .filter(t -> visibleTo(t, viewer))
                .toList();
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

    private void credit(Transaction t, long payer, long receiver, BigDecimal amount) {
        try {
            creditWithRetry(receiver, amount);
        } catch (BankOutcomeUnknownException e) {
            unresolved(t, "Credit to the receiver not confirmed by the bank: " + e.getMessage());
            throw new TransferFailedException("We could not confirm that the money reached the receiver. Our team will "
                    + "settle it. Reference: " + t.getTransactionId());
        } catch (RuntimeException e) {
            refund(t, payer, amount, e);   // the bank definitely did not credit the receiver; always throws
        }
    }

    private void refund(Transaction t, long payer, BigDecimal amount, RuntimeException reason) {
        try {
            creditWithRetry(payer, amount);
        } catch (RuntimeException refundFailure) {
            unresolved(t, "Receiver could not be credited (" + reason.getMessage() + ") and the refund failed ("
                    + refundFailure.getMessage() + ")");
            throw new TransferFailedException("The transfer failed and we could not return your money automatically. "
                    + "Our team will settle it. Reference: " + t.getTransactionId());
        }
        settle(t, TransactionStatus.FAILED, "Receiver could not be credited; payer refunded (" + reason.getMessage() + ")");
        throw new TransferFailedException("The transfer could not be completed. Your money has been returned.");
    }

    // A conflict means the bank changed nothing, so trying again cannot double-apply the deposit.
    private void creditWithRetry(long phno, BigDecimal amount) {
        for (int attempt = 1; ; attempt++) {
            try {
                bank.deposit(phno, amount);
                return;
            } catch (BankConflictException e) {
                if (attempt >= MAX_CREDIT_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    // ---------- bookkeeping ----------

    private Transaction record(long payer, Long receiver, BigDecimal amount, String mode) {
        for (int attempt = 1; ; attempt++) {
            Transaction t = new Transaction();
            t.setTransactionId(nextTransactionId());
            t.setPhno(payer);
            t.setReceiverPhno(receiver);
            t.setAmount(amount);
            t.setMode(mode);
            t.setStatus(TransactionStatus.PENDING);
            t.setCreatedAt(clock.instant());
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
