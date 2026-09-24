package com.example.phonepayservice.client;

import com.example.phonepayservice.dto.BankTransferRequest;
import com.example.phonepayservice.dto.BankUser;
import com.example.phonepayservice.exception.BalanceException;
import com.example.phonepayservice.exception.BankConflictException;
import com.example.phonepayservice.exception.BankOutcomeUnknownException;
import com.example.phonepayservice.exception.BankUnavailableException;
import com.example.phonepayservice.exception.UserNotExistException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * For every call that MOVES money the gateway must separate three situations, because they need opposite reactions:
 * refused (nothing happened, safe to refund), never delivered (nothing happened) and outcome unknown (must not guess).
 * It must also supply the X-Service-Key header the bank now requires on every one of these calls.
 */
@ExtendWith(MockitoExtension.class)
class BankGatewayTest {

    private static final long PHNO = 9876543210L;
    private static final long RECEIVER = 9123456789L;
    private static final String SERVICE_KEY = "test-service-key";

    @Mock
    private BankClient bank;

    // BankGateway takes the service key as a constructor argument (not something Mockito can @InjectMocks
    // meaningfully, since it is a String, not a collaborator), so it is built explicitly in each test instead.
    private BankGateway gateway() {
        return new BankGateway(bank, SERVICE_KEY);
    }

    // ---------- helpers ----------

    private static Request request() {
        return Request.create(Request.HttpMethod.PUT, "http://bank/bank/x", Map.of(), null, StandardCharsets.UTF_8, null);
    }

    /** A real FeignException of the right subtype for the status, as Feign would throw it. */
    private static FeignException bankAnswers(int status, String body) {
        Response response = Response.builder().status(status).reason("test").request(request())
                .headers(Map.of()).body(body, StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("BankClient#call", response);
    }

    private static RetryableException ioProblem(Throwable cause) {
        return new RetryableException(-1, cause.getMessage(), Request.HttpMethod.PUT, cause, (Long) null, request());
    }

    // ---------- findUser (read-only) ----------

    @Test
    void findUser_returnsTheBanksUser() {
        BankUser user = new BankUser();
        user.setName("KUMAR CHARAN");
        user.setBalance(new BigDecimal("1000.00"));
        when(bank.displayUser(SERVICE_KEY, PHNO)).thenReturn(user);

        assertSame(user, gateway().findUser(PHNO));
    }

    @Test
    void everyCall_sendsTheConfiguredServiceKey_notSomeOtherValue() {
        gateway().withdraw(PHNO, new BigDecimal("10"));
        gateway().deposit(PHNO, new BigDecimal("10"));
        BankUser user = new BankUser();
        user.setBalance(new BigDecimal("0"));
        when(bank.displayUser(SERVICE_KEY, PHNO)).thenReturn(user);
        gateway().findUser(PHNO);

        verify(bank).withdrawByphno(SERVICE_KEY, PHNO, new BigDecimal("10"));
        verify(bank).depositByphno(SERVICE_KEY, PHNO, new BigDecimal("10"));
        verify(bank).displayUser(SERVICE_KEY, PHNO);
    }

    @Test
    void findUser_bank400_meansUserNotFound() {
        when(bank.displayUser(anyString(), any(Long.class))).thenThrow(bankAnswers(400, "User not found"));

        UserNotExistException ex = assertThrows(UserNotExistException.class, () -> gateway().findUser(PHNO));

        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void findUser_bank404_meansUserNotFound() {
        when(bank.displayUser(anyString(), any(Long.class))).thenThrow(bankAnswers(404, ""));

        assertThrows(UserNotExistException.class, () -> gateway().findUser(PHNO));
    }

    @Test
    void findUser_emptyAnswer_meansUserNotFound() {
        when(bank.displayUser(anyString(), any(Long.class))).thenReturn(null);

        assertThrows(UserNotExistException.class, () -> gateway().findUser(PHNO));
    }

    // A malformed/adversarial bank response (the user exists, but with no balance field at all) must not NPE
    // deep inside PhonepeService.money() and surface as a raw 500 - it gets treated the same as any other bank
    // response this app cannot trust.
    @Test
    void findUser_missingBalance_meansBankUnavailable() {
        BankUser user = new BankUser();
        user.setName("KUMAR CHARAN");
        user.setBalance(null);
        when(bank.displayUser(anyString(), any(Long.class))).thenReturn(user);

        assertThrows(BankUnavailableException.class, () -> gateway().findUser(PHNO));
    }

    @Test
    void findUser_bankError500_meansBankUnavailable() {
        when(bank.displayUser(anyString(), any(Long.class))).thenThrow(bankAnswers(500, "boom"));

        assertThrows(BankUnavailableException.class, () -> gateway().findUser(PHNO));
    }

    @Test
    void findUser_bankUnreachable_meansBankUnavailable() {
        when(bank.displayUser(anyString(), any(Long.class))).thenThrow(ioProblem(new ConnectException("Connection refused")));

        assertThrows(BankUnavailableException.class, () -> gateway().findUser(PHNO));
    }

    // ---------- withdraw / deposit: the request is passed through ----------

    @Test
    void withdraw_sendsThePhoneAndTheAmount() {
        gateway().withdraw(PHNO, new BigDecimal("250.50"));

        verify(bank).withdrawByphno(SERVICE_KEY, PHNO, new BigDecimal("250.50"));
    }

    @Test
    void deposit_sendsThePhoneAndTheAmount() {
        gateway().deposit(PHNO, new BigDecimal("75.00"));

        verify(bank).depositByphno(SERVICE_KEY, PHNO, new BigDecimal("75.00"));
    }

    // ---------- refused by the bank: nothing happened ----------

    @Test
    void withdraw_insufficientFunds_isRefusedWithTheBanksMessage() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(bankAnswers(400, "Insufficient Funds"));

        BalanceException ex = assertThrows(BalanceException.class, () -> gateway().withdraw(PHNO, new BigDecimal("500")));

        assertEquals("Insufficient Funds", ex.getMessage());
    }

    @Test
    void withdraw_refusedWithNoExplanation_getsAGenericMessage() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(bankAnswers(400, ""));

        BalanceException ex = assertThrows(BalanceException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));

        assertEquals("The bank rejected the request", ex.getMessage());
    }

    @Test
    void withdraw_bankSaysUserNotFound_isUserNotExist() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(bankAnswers(400, "User not found"));

        assertThrows(UserNotExistException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void deposit_bank400_isRefused() {
        when(bank.depositByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(bankAnswers(400, "Amount too low"));

        BalanceException ex = assertThrows(BalanceException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));

        assertEquals("Amount too low", ex.getMessage());
    }

    @Test
    void anyOtherClientError_isRefusedToo() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(bankAnswers(403, "Forbidden"));

        assertThrows(BalanceException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    // ---------- busy: the bank changed nothing, retrying is safe ----------

    @Test
    void bank409_isAConflict_notAFailure() {
        when(bank.depositByphno(anyString(), any(Long.class), any(BigDecimal.class)))
                .thenThrow(bankAnswers(409, "Another request changed the same data at the same time. Please retry."));

        assertThrows(BankConflictException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));
    }

    // ---------- never delivered: nothing happened ----------

    @Test
    void connectionRefused_meansNothingWasSent() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(ioProblem(new ConnectException("Connection refused")));

        assertThrows(BankUnavailableException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void unknownHost_meansNothingWasSent() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(ioProblem(new UnknownHostException("bank")));

        assertThrows(BankUnavailableException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void connectTimeout_meansNothingWasSent() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(ioProblem(new SocketTimeoutException("Connect timed out")));

        assertThrows(BankUnavailableException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    // ---------- outcome unknown: the bank may have done it ----------

    @Test
    void readTimeout_outcomeIsUnknown() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(ioProblem(new SocketTimeoutException("Read timed out")));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void connectionDroppedMidRequest_outcomeIsUnknown() {
        when(bank.depositByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(ioProblem(new java.net.SocketException("Connection reset")));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));
    }

    @Test
    void bankServerError_outcomeIsUnknown() {
        // a 500 can be sent AFTER the bank already committed, so it must never be treated as "nothing happened"
        when(bank.withdrawByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(bankAnswers(500, "Internal Server Error"));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void bankGatewayTimeout_outcomeIsUnknown() {
        when(bank.depositByphno(anyString(), any(Long.class), any(BigDecimal.class))).thenThrow(bankAnswers(504, "Gateway Timeout"));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));
    }

    // ---------- transfer: the same three-way split, sent as one call instead of two ----------

    @Test
    void transfer_sendsPayerReceiverAmountAndTheServiceKey() {
        gateway().transfer(PHNO, RECEIVER, new BigDecimal("250.50"), "key-1");

        verify(bank).transfer(SERVICE_KEY, new BankTransferRequest(PHNO, RECEIVER, new BigDecimal("250.50"), "key-1"));
    }

    @Test
    void transfer_insufficientFunds_isRefusedWithTheBanksMessage() {
        when(bank.transfer(anyString(), any())).thenThrow(bankAnswers(400, "Insufficient Funds"));

        BalanceException ex = assertThrows(BalanceException.class,
                () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("500"), "key-1"));

        assertEquals("Insufficient Funds", ex.getMessage());
    }

    // Recognizing "Payer not found" and "Receiver not found" (not just the literal "User not found" the other
    // three endpoints use) is what lets a stranded transfer be reported as UserNotExistException here too.
    @Test
    void transfer_bankSaysPayerNotFound_isUserNotExist() {
        when(bank.transfer(anyString(), any())).thenThrow(bankAnswers(400, "Payer not found"));

        assertThrows(UserNotExistException.class, () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("5"), "key-1"));
    }

    @Test
    void transfer_bankSaysReceiverNotFound_isUserNotExist() {
        when(bank.transfer(anyString(), any())).thenThrow(bankAnswers(400, "Receiver not found"));

        assertThrows(UserNotExistException.class, () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("5"), "key-1"));
    }

    @Test
    void transfer_toTheSameAccount_isRefused() {
        when(bank.transfer(anyString(), any())).thenThrow(bankAnswers(400, "Cannot transfer to the same account"));

        BalanceException ex = assertThrows(BalanceException.class,
                () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("5"), "key-1"));

        assertEquals("Cannot transfer to the same account", ex.getMessage());
    }

    @Test
    void transfer_conflict_isBankConflictException_notAFailure() {
        when(bank.transfer(anyString(), any()))
                .thenThrow(bankAnswers(409, "Another request changed the same data at the same time. Please retry."));

        assertThrows(BankConflictException.class, () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("5"), "key-1"));
    }

    @Test
    void transfer_bankUnreachable_meansNothingWasSent() {
        when(bank.transfer(anyString(), any())).thenThrow(ioProblem(new ConnectException("Connection refused")));

        assertThrows(BankUnavailableException.class, () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("5"), "key-1"));
    }

    @Test
    void transfer_readTimeout_outcomeIsUnknown() {
        when(bank.transfer(anyString(), any())).thenThrow(ioProblem(new SocketTimeoutException("Read timed out")));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("5"), "key-1"));
    }

    @Test
    void transfer_bankServerError_outcomeIsUnknown() {
        // a 500 can be sent AFTER the bank already committed, so it must never be treated as "nothing happened"
        when(bank.transfer(anyString(), any())).thenThrow(bankAnswers(500, "Internal Server Error"));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().transfer(PHNO, RECEIVER, new BigDecimal("5"), "key-1"));
    }
}
