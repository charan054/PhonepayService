package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.ClockConfig;
import com.example.phonepayservice.configuration.WebConfig;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.LoginResponse;
import com.example.phonepayservice.dto.PageResponse;
import com.example.phonepayservice.dto.PayeeResponse;
import com.example.phonepayservice.dto.ProfileResponse;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.exception.AccountLockedException;
import com.example.phonepayservice.exception.BalanceException;
import com.example.phonepayservice.exception.BankConflictException;
import com.example.phonepayservice.exception.BankUnavailableException;
import com.example.phonepayservice.exception.InvalidCredentialsException;
import com.example.phonepayservice.exception.InvalidRequestException;
import com.example.phonepayservice.exception.PayeeNotFoundException;
import com.example.phonepayservice.exception.TransactionNotFoundException;
import com.example.phonepayservice.exception.TransferFailedException;
import com.example.phonepayservice.exception.UserNotExistException;
import com.example.phonepayservice.exception.UserNotRegisteredException;
import com.example.phonepayservice.service.PayeeService;
import com.example.phonepayservice.service.PhonepeService;
import com.example.phonepayservice.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "test" profile: gives spring.datasource.password a value, so these tests don't need DB_PASSWORD on the machine.
@WebMvcTest(PhonepeController.class)
@Import({WebConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class PhonepeControllerTest {

    private static final long CALLER = 9876543210L;
    private static final long RECEIVER = 9123456789L;
    private static final String TOKEN = "good-token";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhonepeService phonepeService;
    @MockitoBean
    private PayeeService payeeService;
    @MockitoBean
    private SessionService sessionService;

    @BeforeEach
    void loginTheCaller() {
        when(sessionService.authenticate(TOKEN)).thenReturn(CALLER);
        when(sessionService.authenticate(null)).thenThrow(new UserNotRegisteredException("Login required"));
        when(sessionService.authenticate("bad-token")).thenThrow(new UserNotRegisteredException("Invalid session. Please login again."));
    }

    private MockHttpServletRequestBuilder asCaller(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    private Transaction transaction(long id, long payer, Long receiver, TransactionStatus status) {
        Transaction t = new Transaction();
        t.setTransactionId(id);
        t.setPhno(payer);
        t.setReceiverPhno(receiver);
        t.setMode(receiver == null ? "Payment" : "Transfer");
        t.setAmount(new BigDecimal("250.00"));
        t.setStatus(status);
        t.setFailureReason("internal detail that must never reach the client");
        t.setCreatedAt(NOW);
        return t;
    }

    private static <T> PageResponse<T> pageOf(List<T> items) {
        return new PageResponse<>(items, 0, items.size(), items.size(), 1);
    }

    // ============ authentication: every endpoint except /login needs a valid token ============

    private static List<MockHttpServletRequestBuilder> protectedEndpoints() {
        return List.of(
                post("/phonepe/logout"),
                get("/phonepe/profile"),
                get("/phonepe/balance"),
                post("/phonepe/sendmoney").contentType(MediaType.APPLICATION_JSON).content("{\"receiverPhno\":9123456789,\"amount\":10}"),
                post("/phonepe/makepayment").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"),
                get("/phonepe/transactions"),
                get("/phonepe/transactions/100000"));
    }

    @Test
    void everyProtectedEndpoint_withoutAToken_returns401() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : protectedEndpoints()) {
            mockMvc.perform(endpoint)
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string("Login required"));
        }
        verifyNoInteractions(phonepeService);
    }

    @Test
    void everyProtectedEndpoint_withABadToken_returns401() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : protectedEndpoints()) {
            mockMvc.perform(endpoint.header("Authorization", "Bearer bad-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string("Invalid session. Please login again."));
        }
        verifyNoInteractions(phonepeService);
    }

    @Test
    void aHeaderThatIsNotABearerToken_isTreatedAsNoToken() throws Exception {
        mockMvc.perform(get("/phonepe/profile").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/phonepe/profile").header("Authorization", "good-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(phonepeService);
    }

    @Test
    void bearerKeyword_isCaseInsensitive() throws Exception {
        when(phonepeService.balance(CALLER)).thenReturn(new BalanceResponse(CALLER, new BigDecimal("10.00")));

        mockMvc.perform(get("/phonepe/balance").header("Authorization", "bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    // ============ POST /phonepe/login ============

    @Test
    void login_needsNoToken_andReturnsTheSessionToken() throws Exception {
        when(phonepeService.login(CALLER, "1234")).thenReturn(new LoginResponse("abc123", NOW.plusSeconds(1800), CALLER, "KUMAR CHARAN"));

        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("abc123"))
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"))
                .andExpect(jsonPath("$.phno").value(CALLER));

        verify(sessionService, never()).authenticate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5876543210", "987654321", "98765432101", "0"})
    void login_invalidPhoneNumber_returns400(String phno) throws Exception {
        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":" + phno + ",\"pin\":\"1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void login_missingPhoneNumber_returns400() throws Exception {
        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Phone number is required"));
    }

    @Test
    void login_missingPin_returns400() throws Exception {
        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("PIN is required"));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void login_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // Only a 4-6 digit PIN can ever have been set on the bank side, so anything else is rejected here rather
    // than being relayed to the bank - an unbounded PIN string would otherwise trip BCrypt's 72-byte input
    // limit there and come back as a bare 500, which BankGateway.login() turns into a misleading 503.
    @Test
    void login_pinTooLong_returns400_neverReachesTheService() throws Exception {
        String oversizedPin = "1".repeat(100);

        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phno\":9876543210,\"pin\":\"" + oversizedPin + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("PIN must be 4 to 6 digits"));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void login_pinWithNonDigits_returns400() throws Exception {
        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"12ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("PIN must be 4 to 6 digits"));

        verifyNoInteractions(phonepeService);
    }

    // The bank keeps this generic on purpose (wrong PIN and "no such phone" look identical), so this must not be
    // narrowed to a 404 the way it used to be when login only ever looked the phone number up.
    @Test
    void login_wrongPinOrUnknownUser_returns401() throws Exception {
        when(phonepeService.login(CALLER, "0000")).thenThrow(new InvalidCredentialsException("Invalid phone number or PIN"));

        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"0000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid phone number or PIN"));
    }

    @Test
    void login_accountLocked_returns423() throws Exception {
        when(phonepeService.login(CALLER, "1234"))
                .thenThrow(new AccountLockedException("Too many failed attempts. Try again after 2026-09-23T10:15:00Z."));

        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"1234\"}"))
                .andExpect(status().isLocked())
                .andExpect(content().string("Too many failed attempts. Try again after 2026-09-23T10:15:00Z."));
    }

    @Test
    void login_bankDown_returns503() throws Exception {
        when(phonepeService.login(CALLER, "1234")).thenThrow(new BankUnavailableException("The bank service is unavailable. Please try again later.", null));

        mockMvc.perform(post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"1234\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("The bank service is unavailable. Please try again later."));
    }

    // ============ POST /phonepe/login: rate limiting per address ============

    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void login_tooManyAttemptsFromTheSameAddress_is429() throws Exception {
        when(phonepeService.login(CALLER, "1234")).thenReturn(new LoginResponse("abc123", NOW.plusSeconds(1800), CALLER, "KUMAR CHARAN"));

        for (int i = 0; i < WebConfig.DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS; i++) {
            mockMvc.perform(loginRequest().with(fromAddress("203.0.113.5"))).andExpect(status().isOk());
        }

        mockMvc.perform(loginRequest().with(fromAddress("203.0.113.5")))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string("Too many login attempts from this address. Please wait a minute and try again."));
    }

    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void login_rateLimitIsPerAddress_anotherAddressIsUnaffected() throws Exception {
        when(phonepeService.login(CALLER, "1234")).thenReturn(new LoginResponse("abc123", NOW.plusSeconds(1800), CALLER, "KUMAR CHARAN"));

        for (int i = 0; i < WebConfig.DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS; i++) {
            mockMvc.perform(loginRequest().with(fromAddress("203.0.113.5"))).andExpect(status().isOk());
        }
        mockMvc.perform(loginRequest().with(fromAddress("203.0.113.5"))).andExpect(status().isTooManyRequests());

        mockMvc.perform(loginRequest().with(fromAddress("203.0.113.9"))).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder loginRequest() {
        return post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":9876543210,\"pin\":\"1234\"}");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor fromAddress(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    // ============ POST /phonepe/logout ============

    @Test
    void logout_returns204_andEndsThatTokensSession() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/logout")))
                .andExpect(status().isNoContent());

        verify(phonepeService).logout(TOKEN);
    }

    // ============ GET /phonepe/profile and /balance ============

    @Test
    void profile_isTheCallersOwn() throws Exception {
        when(phonepeService.profile(CALLER)).thenReturn(new ProfileResponse(CALLER, "KUMAR CHARAN", 1000000000L, new BigDecimal("1234.50")));

        mockMvc.perform(asCaller(get("/phonepe/profile")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"))
                .andExpect(jsonPath("$.acno").value(1000000000L))
                .andExpect(jsonPath("$.balance").value(1234.5))
                .andExpect(jsonPath("$.aadharNumber").doesNotExist());
    }

    @Test
    void profile_ignoresAnyPhoneNumberInTheRequest() throws Exception {
        when(phonepeService.profile(CALLER)).thenReturn(new ProfileResponse(CALLER, "KUMAR CHARAN", 1000000000L, BigDecimal.TEN));

        mockMvc.perform(asCaller(get("/phonepe/profile").param("phno", "9000000001")))
                .andExpect(status().isOk());

        verify(phonepeService).profile(CALLER);
        verify(phonepeService, never()).profile(9000000001L);
    }

    @Test
    void balance_isTheCallersOwn() throws Exception {
        when(phonepeService.balance(CALLER)).thenReturn(new BalanceResponse(CALLER, new BigDecimal("500.00")));

        mockMvc.perform(asCaller(get("/phonepe/balance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500.0))
                .andExpect(jsonPath("$.phno").value(CALLER));
    }

    @Test
    void balance_bankDown_returns503() throws Exception {
        when(phonepeService.balance(CALLER)).thenThrow(new BankUnavailableException("The bank service is unavailable. Please try again later.", null));

        mockMvc.perform(asCaller(get("/phonepe/balance")))
                .andExpect(status().isServiceUnavailable());
    }

    // ============ POST /phonepe/sendmoney ============

    @Test
    void sendMoney_success_returnsTheTransactionFromTheCallersPointOfView() throws Exception {
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), any(), any()))
                .thenReturn(transaction(100000, CALLER, RECEIVER, TransactionStatus.COMPLETED));

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(100000))
                .andExpect(jsonPath("$.direction").value("DEBIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(250.0))
                .andExpect(jsonPath("$.receiverPhno").value(RECEIVER))
                .andExpect(jsonPath("$.failureReason").doesNotExist());   // internal detail never leaves the server
    }

    @Test
    void sendMoney_theCallerCannotChooseWhoPays() throws Exception {
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), any(), any()))
                .thenReturn(transaction(100000, CALLER, RECEIVER, TransactionStatus.COMPLETED));

        // a client that tries to name a different payer in the body or the query string
        mockMvc.perform(asCaller(post("/phonepe/sendmoney").param("payer", "9000000001")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerPhno\":9000000001,\"phno\":9000000001,\"receiverPhno\":9123456789,\"amount\":250}"))
                .andExpect(status().isOk());

        verify(phonepeService).sendMoney(eq(CALLER), eq(RECEIVER), argThat(a -> a.compareTo(new BigDecimal("250")) == 0), any(), any());
        verify(phonepeService, never()).sendMoney(eq(9000000001L), anyLong(), any(), any(), any());
    }

    @Test
    void sendMoney_passesTheNoteThrough_andReturnsItInTheResponse() throws Exception {
        Transaction withNote = transaction(100000, CALLER, RECEIVER, TransactionStatus.COMPLETED);
        withNote.setNote("rent");
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), eq("rent"), any())).thenReturn(withNote);

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250,\"note\":\"rent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("rent"));
    }

    @Test
    void sendMoney_noteTooLong_returns400() throws Exception {
        String tooLong = "x".repeat(141);

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250,\"note\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Note can be at most 140 characters"));

        verifyNoInteractions(phonepeService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "0.001"})
    void sendMoney_badAmount_returns400(String amount) throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":" + amount + "}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(phonepeService);
    }

    @Test
    void sendMoney_zeroAmount_explainsWhy() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":0}"))
                .andExpect(content().string("Amount too low"));
    }

    @Test
    void sendMoney_moreThanTwoDecimals_explainsWhy() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":1.234}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount can have at most 2 decimal places"));
    }

    @Test
    void sendMoney_missingAmount_returns400() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount is required"));
    }

    @Test
    void sendMoney_invalidReceiver_returns400() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":5876543210,\"amount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));
    }

    @Test
    void sendMoney_missingReceiver_returns400() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Receiver phone number is required"));
    }

    @Test
    void sendMoney_insufficientFunds_returns400() throws Exception {
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), any(), any())).thenThrow(new BalanceException("Insufficient Funds"));

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));
    }

    @Test
    void sendMoney_unknownReceiver_returns404() throws Exception {
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), any(), any())).thenThrow(new UserNotExistException("User not found"));

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));
    }

    @Test
    void sendMoney_transferFailed_returns502_withWhatHappenedToTheMoney() throws Exception {
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), any(), any()))
                .thenThrow(new TransferFailedException("The transfer could not be completed. Your money has been returned."));

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("The transfer could not be completed. Your money has been returned."));
    }

    @Test
    void sendMoney_bankBusy_returns409Retry() throws Exception {
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), any(), any())).thenThrow(new BankConflictException("busy"));

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250}"))
                .andExpect(status().isConflict())
                .andExpect(content().string("Another request changed the same data at the same time. Please retry."));
    }

    @Test
    void sendMoney_bankDown_returns503() throws Exception {
        when(phonepeService.sendMoney(eq(CALLER), eq(RECEIVER), any(), any(), any())).thenThrow(new BankUnavailableException("The bank service is unavailable. Please try again later.", null));

        mockMvc.perform(asCaller(post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":9123456789,\"amount\":250}"))
                .andExpect(status().isServiceUnavailable());
    }

    // ============ POST /phonepe/sendmoney, /phonepe/makepayment: rate limiting per account ============

    // Uses its own token/account, never CALLER's, so this test's budget never mixes with the many sendMoney/
    // makePayment tests elsewhere that already use CALLER - and never leaks into them either, thanks to
    // @DirtiesContext.
    private static final long RATE_LIMITED_CALLER = 9000000001L;
    private static final String RATE_LIMITED_TOKEN = "rate-limit-test-token";

    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMoney_tooManyRequestsFromTheSameAccount_is429() throws Exception {
        when(sessionService.authenticate(RATE_LIMITED_TOKEN)).thenReturn(RATE_LIMITED_CALLER);
        when(phonepeService.sendMoney(eq(RATE_LIMITED_CALLER), eq(RECEIVER), any(), any(), any()))
                .thenReturn(transaction(100000, RATE_LIMITED_CALLER, RECEIVER, TransactionStatus.COMPLETED));

        for (int i = 0; i < WebConfig.DEFAULT_MAX_SENDMONEY_ATTEMPTS_PER_ACCOUNT; i++) {
            mockMvc.perform(sendMoneyRequest(RATE_LIMITED_TOKEN)).andExpect(status().isOk());
        }

        mockMvc.perform(sendMoneyRequest(RATE_LIMITED_TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string("Too many payment requests. Please wait a minute and try again."));
    }

    // makePayment debits through the exact same bank call as sendMoney; the two must share one combined budget
    // per account rather than makePayment being left with no limit of its own.
    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void sendMoneyAndMakePayment_shareOneRateLimitBudgetPerAccount_tooManyRequests_is429() throws Exception {
        when(sessionService.authenticate(RATE_LIMITED_TOKEN)).thenReturn(RATE_LIMITED_CALLER);
        when(phonepeService.makePayment(eq(RATE_LIMITED_CALLER), any(), any(), any()))
                .thenReturn(transaction(100000, RATE_LIMITED_CALLER, null, TransactionStatus.COMPLETED));

        for (int i = 0; i < WebConfig.DEFAULT_MAX_SENDMONEY_ATTEMPTS_PER_ACCOUNT; i++) {
            mockMvc.perform(makePaymentRequest(RATE_LIMITED_TOKEN)).andExpect(status().isOk());
        }

        mockMvc.perform(sendMoneyRequest(RATE_LIMITED_TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string("Too many payment requests. Please wait a minute and try again."));
    }

    private static MockHttpServletRequestBuilder sendMoneyRequest(String token) {
        return post("/phonepe/sendmoney").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"receiverPhno\":9123456789,\"amount\":250}");
    }

    private static MockHttpServletRequestBuilder makePaymentRequest(String token) {
        return post("/phonepe/makepayment").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}");
    }

    // ============ POST /phonepe/makepayment ============

    @Test
    void makePayment_success() throws Exception {
        when(phonepeService.makePayment(eq(CALLER), any(), any(), any())).thenReturn(transaction(100001, CALLER, null, TransactionStatus.COMPLETED));

        mockMvc.perform(asCaller(post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":99.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("Payment"))
                .andExpect(jsonPath("$.receiverPhno").doesNotExist())
                .andExpect(jsonPath("$.direction").value("DEBIT"));

        verify(phonepeService).makePayment(eq(CALLER), argThat(a -> a.compareTo(new BigDecimal("99.5")) == 0), any(), any());
    }

    @Test
    void makePayment_passesTheNoteThrough_andReturnsItInTheResponse() throws Exception {
        Transaction withNote = transaction(100001, CALLER, null, TransactionStatus.COMPLETED);
        withNote.setNote("movie tickets");
        when(phonepeService.makePayment(eq(CALLER), any(), eq("movie tickets"), any())).thenReturn(withNote);

        mockMvc.perform(asCaller(post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":99.5,\"note\":\"movie tickets\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("movie tickets"));
    }

    @Test
    void makePayment_noteTooLong_returns400() throws Exception {
        String tooLong = "x".repeat(141);

        mockMvc.perform(asCaller(post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":99.5,\"note\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Note can be at most 140 characters"));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void makePayment_badAmount_returns400() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Amount too low"));

        verifyNoInteractions(phonepeService);
    }

    @Test
    void makePayment_insufficientFunds_returns400() throws Exception {
        when(phonepeService.makePayment(eq(CALLER), any(), any(), any())).thenThrow(new BalanceException("Insufficient Funds"));

        mockMvc.perform(asCaller(post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));
    }

    // ============ GET /phonepe/transactions ============

    @Test
    void transactions_areShownFromTheCallersPointOfView() throws Exception {
        when(phonepeService.transactionsOf(eq(CALLER), anyInt(), anyInt(), any(), any())).thenReturn(pageOf(List.of(
                transaction(100002, RECEIVER, CALLER, TransactionStatus.COMPLETED),    // money the caller received
                transaction(100001, CALLER, RECEIVER, TransactionStatus.COMPLETED))));  // money the caller sent

        mockMvc.perform(asCaller(get("/phonepe/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].failureReason").doesNotExist());
    }

    @Test
    void transactions_ignoreAnyPhoneNumberInTheRequest() throws Exception {
        when(phonepeService.transactionsOf(eq(CALLER), anyInt(), anyInt(), any(), any())).thenReturn(pageOf(List.of()));

        mockMvc.perform(asCaller(get("/phonepe/transactions").param("phno", "9000000001")))
                .andExpect(status().isOk());

        verify(phonepeService).transactionsOf(eq(CALLER), anyInt(), anyInt(), any(), any());
        verify(phonepeService, never()).transactionsOf(eq(9000000001L), anyInt(), anyInt(), any(), any());
    }

    @Test
    void transactions_passesPageAndSizeThrough() throws Exception {
        when(phonepeService.transactionsOf(eq(CALLER), eq(2), eq(5), any(), any())).thenReturn(pageOf(List.of()));

        mockMvc.perform(asCaller(get("/phonepe/transactions")).param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        verify(phonepeService).transactionsOf(eq(CALLER), eq(2), eq(5), any(), any());
    }

    @Test
    void transactions_defaultsToPageZeroAndTheDefaultSize() throws Exception {
        when(phonepeService.transactionsOf(eq(CALLER), eq(0), eq(PhonepeService.DEFAULT_PAGE_SIZE), any(), any())).thenReturn(pageOf(List.of()));

        mockMvc.perform(asCaller(get("/phonepe/transactions"))).andExpect(status().isOk());

        verify(phonepeService).transactionsOf(eq(CALLER), eq(0), eq(PhonepeService.DEFAULT_PAGE_SIZE), any(), any());
    }

    @Test
    void transactions_passesFromAndToThrough() throws Exception {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");
        when(phonepeService.transactionsOf(CALLER, 0, PhonepeService.DEFAULT_PAGE_SIZE, from, to)).thenReturn(pageOf(List.of()));

        mockMvc.perform(asCaller(get("/phonepe/transactions")).param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk());

        verify(phonepeService).transactionsOf(CALLER, 0, PhonepeService.DEFAULT_PAGE_SIZE, from, to);
    }

    @Test
    void transaction_byId_success() throws Exception {
        when(phonepeService.transaction(CALLER, 100000)).thenReturn(transaction(100000, CALLER, RECEIVER, TransactionStatus.COMPLETED));

        mockMvc.perform(asCaller(get("/phonepe/transactions/100000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(100000));
    }

    @Test
    void transaction_byId_notTheCallers_returns404() throws Exception {
        when(phonepeService.transaction(CALLER, 100000)).thenThrow(new TransactionNotFoundException("Transaction not found"));

        mockMvc.perform(asCaller(get("/phonepe/transactions/100000")))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Transaction not found"));
    }

    @Test
    void transaction_nonNumericId_returns400() throws Exception {
        mockMvc.perform(asCaller(get("/phonepe/transactions/abc")))
                .andExpect(status().isBadRequest());
    }

    // ============ POST /phonepe/payees ============

    @Test
    void savePayee_returnsTheSavedPayee() throws Exception {
        when(payeeService.save(CALLER, RECEIVER, "Ravi")).thenReturn(new PayeeResponse(RECEIVER, "Ravi"));

        mockMvc.perform(asCaller(post("/phonepe/payees")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payeePhno\":9123456789,\"nickname\":\"Ravi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payeePhno").value(RECEIVER))
                .andExpect(jsonPath("$.nickname").value("Ravi"));
    }

    @Test
    void savePayee_noNickname_isAllowed() throws Exception {
        when(payeeService.save(CALLER, RECEIVER, null)).thenReturn(new PayeeResponse(RECEIVER, null));

        mockMvc.perform(asCaller(post("/phonepe/payees")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payeePhno\":9123456789}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").doesNotExist());
    }

    @Test
    void savePayee_invalidPhoneNumber_returns400() throws Exception {
        mockMvc.perform(asCaller(post("/phonepe/payees")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payeePhno\":123,\"nickname\":\"Ravi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));

        verifyNoInteractions(payeeService);
    }

    @Test
    void savePayee_nicknameTooLong_returns400() throws Exception {
        String tooLong = "x".repeat(51);

        mockMvc.perform(asCaller(post("/phonepe/payees")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payeePhno\":9123456789,\"nickname\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Nickname can be at most 50 characters"));
    }

    @Test
    void savePayee_yourself_returns400() throws Exception {
        when(payeeService.save(CALLER, CALLER, "Me")).thenThrow(new InvalidRequestException("You cannot save yourself as a payee"));

        mockMvc.perform(asCaller(post("/phonepe/payees")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payeePhno\":9876543210,\"nickname\":\"Me\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("You cannot save yourself as a payee"));
    }

    @Test
    void savePayee_needsAToken() throws Exception {
        mockMvc.perform(post("/phonepe/payees").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payeePhno\":9123456789}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(payeeService);
    }

    // ============ GET /phonepe/payees ============

    @Test
    void payees_returnsTheCallersSavedPayees() throws Exception {
        when(payeeService.listPayees(CALLER)).thenReturn(List.of(new PayeeResponse(RECEIVER, "Ravi")));

        mockMvc.perform(asCaller(get("/phonepe/payees")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payeePhno").value(RECEIVER))
                .andExpect(jsonPath("$[0].nickname").value("Ravi"));
    }

    @Test
    void payees_none_returnsAnEmptyList() throws Exception {
        when(payeeService.listPayees(CALLER)).thenReturn(List.of());

        mockMvc.perform(asCaller(get("/phonepe/payees")))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    // ============ DELETE /phonepe/payees/{payeePhno} ============

    @Test
    void deletePayee_removesIt() throws Exception {
        mockMvc.perform(asCaller(delete("/phonepe/payees/9123456789")))
                .andExpect(status().isNoContent());

        verify(payeeService).delete(CALLER, RECEIVER);
    }

    @Test
    void deletePayee_notSaved_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new PayeeNotFoundException("No saved payee with that phone number"))
                .when(payeeService).delete(CALLER, RECEIVER);

        mockMvc.perform(asCaller(delete("/phonepe/payees/9123456789")))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No saved payee with that phone number"));
    }

    // ============ the old, unauthenticated endpoints are gone ============

    @ParameterizedTest
    @ValueSource(strings = {"/phonepe/all", "/phonepe/checkBalance", "/phonepe/transactionByphno", "/phonepe/transactionbyacno"})
    void oldEndpoints_noLongerExist(String path) throws Exception {
        mockMvc.perform(asCaller(get(path)))
                .andExpect(status().isNotFound());
    }
}
