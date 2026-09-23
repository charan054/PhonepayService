package com.example.phonepayservice.client;

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
        when(bank.displayUser(SERVICE_KEY, PHNO)).thenReturn(user);

        assertSame(user, gateway().findUser(PHNO));
    }

    @Test
    void everyCall_sendsTheConfiguredServiceKey_notSomeOtherValue() {
        gateway().withdraw(PHNO, new BigDecimal("10"));
        gateway().deposit(PHNO, new BigDecimal("10"));
        when(bank.displayUser(SERVICE_KEY, PHNO)).thenReturn(new BankUser());
        gateway().findUser(PHNO);

        verify(bank).withdrawByphno(SERVICE_KEY, PHNO, 10.0);
        verify(bank).depositByphno(SERVICE_KEY, PHNO, 10.0);
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

        verify(bank).withdrawByphno(SERVICE_KEY, PHNO, 250.5);
    }

    @Test
    void deposit_sendsThePhoneAndTheAmount() {
        gateway().deposit(PHNO, new BigDecimal("75.00"));

        verify(bank).depositByphno(SERVICE_KEY, PHNO, 75.0);
    }

    // ---------- refused by the bank: nothing happened ----------

    @Test
    void withdraw_insufficientFunds_isRefusedWithTheBanksMessage() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(bankAnswers(400, "Insufficient Funds"));

        BalanceException ex = assertThrows(BalanceException.class, () -> gateway().withdraw(PHNO, new BigDecimal("500")));

        assertEquals("Insufficient Funds", ex.getMessage());
    }

    @Test
    void withdraw_refusedWithNoExplanation_getsAGenericMessage() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(bankAnswers(400, ""));

        BalanceException ex = assertThrows(BalanceException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));

        assertEquals("The bank rejected the request", ex.getMessage());
    }

    @Test
    void withdraw_bankSaysUserNotFound_isUserNotExist() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(bankAnswers(400, "User not found"));

        assertThrows(UserNotExistException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void deposit_bank400_isRefused() {
        when(bank.depositByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(bankAnswers(400, "Amount too low"));

        BalanceException ex = assertThrows(BalanceException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));

        assertEquals("Amount too low", ex.getMessage());
    }

    @Test
    void anyOtherClientError_isRefusedToo() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(bankAnswers(403, "Forbidden"));

        assertThrows(BalanceException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    // ---------- busy: the bank changed nothing, retrying is safe ----------

    @Test
    void bank409_isAConflict_notAFailure() {
        when(bank.depositByphno(anyString(), any(Long.class), any(Double.class)))
                .thenThrow(bankAnswers(409, "Another request changed the same data at the same time. Please retry."));

        assertThrows(BankConflictException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));
    }

    // ---------- never delivered: nothing happened ----------

    @Test
    void connectionRefused_meansNothingWasSent() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(ioProblem(new ConnectException("Connection refused")));

        assertThrows(BankUnavailableException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void unknownHost_meansNothingWasSent() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(ioProblem(new UnknownHostException("bank")));

        assertThrows(BankUnavailableException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void connectTimeout_meansNothingWasSent() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(ioProblem(new SocketTimeoutException("Connect timed out")));

        assertThrows(BankUnavailableException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    // ---------- outcome unknown: the bank may have done it ----------

    @Test
    void readTimeout_outcomeIsUnknown() {
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(ioProblem(new SocketTimeoutException("Read timed out")));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void connectionDroppedMidRequest_outcomeIsUnknown() {
        when(bank.depositByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(ioProblem(new java.net.SocketException("Connection reset")));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));
    }

    @Test
    void bankServerError_outcomeIsUnknown() {
        // a 500 can be sent AFTER the bank already committed, so it must never be treated as "nothing happened"
        when(bank.withdrawByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(bankAnswers(500, "Internal Server Error"));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().withdraw(PHNO, new BigDecimal("5")));
    }

    @Test
    void bankGatewayTimeout_outcomeIsUnknown() {
        when(bank.depositByphno(anyString(), any(Long.class), any(Double.class))).thenThrow(bankAnswers(504, "Gateway Timeout"));

        assertThrows(BankOutcomeUnknownException.class, () -> gateway().deposit(PHNO, new BigDecimal("5")));
    }
}
