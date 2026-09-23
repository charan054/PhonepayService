package com.example.phonepayservice.client;

import com.example.phonepayservice.dto.BankLoginRequest;
import com.example.phonepayservice.dto.BankLoginResult;
import com.example.phonepayservice.dto.BankTransferRequest;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.exception.AccountLockedException;
import com.example.phonepayservice.exception.BalanceException;
import com.example.phonepayservice.exception.BankConflictException;
import com.example.phonepayservice.exception.BankOutcomeUnknownException;
import com.example.phonepayservice.exception.BankUnavailableException;
import com.example.phonepayservice.exception.InvalidCredentialsException;
import com.example.phonepayservice.exception.UserNotExistException;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.function.Supplier;

/**
 * The one place that understands the bank's answers. For every call that MOVES money it must tell apart:
 * <ul>
 *   <li>refused (4xx): the bank did nothing, so it is safe to give up or refund;</li>
 *   <li>never delivered (could not connect): the bank did nothing;</li>
 *   <li>outcome unknown (timeout, 5xx): the bank may have done it, so nobody may guess.</li>
 * </ul>
 * Also supplies the X-Service-Key header the bank now requires on all three of these endpoints.
 */
@Component
public class BankGateway {
    private final BankClient bank;
    private final String serviceKey;

    public BankGateway(BankClient bank, @Value("${bank.service.api-key}") String serviceKey) {
        this.bank = bank;
        this.serviceKey = serviceKey;
    }

    /**
     * Verifies the PIN with the bank - the bank IS the source of truth for credentials, so a wrong PIN, an
     * unknown phone number, or a locked account must all fail here, not just be waved through.
     */
    public BankLoginResult login(long phno, String pin) {
        try {
            return bank.login(new BankLoginRequest(phno, pin));
        } catch (FeignException.Unauthorized e) {
            throw new InvalidCredentialsException(orDefault(e.contentUTF8(), "Invalid phone number or PIN"));
        } catch (FeignException.FeignClientException e) {
            if (e.status() == 423) {
                throw new AccountLockedException(orDefault(e.contentUTF8(), "Too many failed attempts. Please try again later."));
            }
            throw new BankUnavailableException("The bank service is unavailable. Please try again later.", e);
        } catch (RetryableException e) {
            throw new BankUnavailableException("The bank service is unavailable. Please try again later.", e);
        } catch (FeignException e) {
            throw new BankUnavailableException("The bank service is unavailable. Please try again later.", e);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Read-only, so any failure is simply "try again later". */
    public BankUser findUser(long phno) {
        try {
            BankUser user = bank.displayUser(serviceKey, phno);
            if (user == null) {
                throw new UserNotExistException("User not found");
            }
            return user;
        } catch (FeignException.BadRequest | FeignException.NotFound e) {
            throw new UserNotExistException("User not found");
        } catch (FeignException e) {
            throw new BankUnavailableException("The bank service is unavailable. Please try again later.", e);
        }
    }

    public void withdraw(long phno, BigDecimal amount) {
        moveMoney(() -> bank.withdrawByphno(serviceKey, phno, amount.doubleValue()));
    }

    public void deposit(long phno, BigDecimal amount) {
        moveMoney(() -> bank.depositByphno(serviceKey, phno, amount.doubleValue()));
    }

    // Idempotent by construction (see BankTransferRequest): retrying with the same idempotencyKey after a
    // BankConflictException or BankOutcomeUnknownException is always safe.
    public void transfer(long payerPhno, long receiverPhno, BigDecimal amount, String idempotencyKey) {
        moveMoney(() -> bank.transfer(serviceKey, new BankTransferRequest(payerPhno, receiverPhno, amount, idempotencyKey)));
    }

    private void moveMoney(Supplier<String> call) {
        try {
            call.get();
        } catch (FeignException.Conflict e) {
            throw new BankConflictException("The bank was busy with another request on this account. Please retry.");
        } catch (FeignException.FeignClientException e) {
            throw refused(e);
        } catch (RetryableException e) {
            if (neverConnected(e)) {
                throw new BankUnavailableException("The bank service is unavailable. Please try again later.", e);
            }
            throw new BankOutcomeUnknownException("No answer from the bank", e);
        } catch (FeignException e) {
            throw new BankOutcomeUnknownException("The bank answered with an error (" + e.status() + ")", e);
        }
    }

    private RuntimeException refused(FeignException e) {
        String reason = e.contentUTF8();
        // Matches "User not found" (displayuser/withdraw/deposit) as well as "Payer not found" and
        // "Receiver not found" (transfer) - the caller only needs to know someone in the request doesn't exist.
        if (reason != null && reason.toLowerCase().contains("not found")) {
            return new UserNotExistException("User not found");
        }
        return new BalanceException(reason == null || reason.isBlank() ? "The bank rejected the request" : reason);
    }

    // true only when we are sure the request never left this machine
    private boolean neverConnected(RetryableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException) {
            return true;
        }
        String name = cause == null ? "" : cause.getClass().getSimpleName();
        String message = cause == null || cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
        return name.contains("ConnectTimeout") || message.contains("connect timed out");
    }
}
