package com.example.phonepayservice;

import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.entity.UserSession;
import com.example.phonepayservice.repository.TransactionRepository;
import com.example.phonepayservice.repository.UserSessionRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end: real controller, real service, real Feign client, real (in-memory) database.
 * Only the bank is fake: a WireMock server on a random port, so the real bank app on 8080 is never touched.
 */
// Login and sendMoney are both rate-limited (see WebConfig); this suite legitimately calls them many times over
// its run, from one simulated address and largely one simulated account, so it needs much higher budgets than
// production traffic from one real address/account would ever need.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "phonepe.login.rate-limit.max-attempts=1000",
        "phonepe.sendmoney.rate-limit.max-attempts=1000"
})
class PhonepeIntegrationTest {

    private static final long ASHA = 9876543210L;
    private static final long RAVI = 9123456789L;
    private static final long MEENA = 9000000001L;
    private static final String PIN = "1234";
    private static final String TRANSFER = "POST /bank/transfer";

    @RegisterExtension
    static WireMockExtension bank = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointFeignAtTheFakeBank(DynamicPropertyRegistry registry) {
        registry.add("bank.service.url", bank::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private UserSessionRepository sessionRepository;

    @BeforeEach
    void emptyTheDatabase() {
        transactionRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    // ============ the fake bank ============

    // Registers this phone number both for the login PIN check and for the account lookups made after login
    // (profile, balance, ...): virtually every test that needs "the bank knows this person" needs both.
    private void bankHasUser(long phno, String name, double balance) {
        bank.stubFor(WireMock.get(urlPathEqualTo("/bank/displayuser")).withQueryParam("phno", equalTo("" + phno))
                .willReturn(okJson("{\"userId\":1,\"acno\":1000000000,\"name\":\"" + name + "\",\"aadharNumber\":111111111111,"
                        + "\"phno\":" + phno + ",\"balance\":" + balance + "}")));
        bankLoginSucceeds(phno, name);
    }

    private void bankLoginSucceeds(long phno, String name) {
        bank.stubFor(WireMock.post(urlPathEqualTo("/bank/login"))
                .willReturn(okJson("{\"token\":\"bank-token\",\"expiresAt\":\"2099-01-01T00:00:00Z\","
                        + "\"phno\":" + phno + ",\"name\":\"" + name + "\"}")));
    }

    private void bankLoginFails(int status, String body) {
        bank.stubFor(WireMock.post(urlPathEqualTo("/bank/login"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "text/plain").withBody(body)));
    }

    private void withdrawSucceeds(long phno) {
        bank.stubFor(WireMock.put(urlPathEqualTo("/bank/withdrawByphno")).withQueryParam("phno", equalTo("" + phno))
                .willReturn(okForContentType("text/plain", "Withdraw Successful")));
    }

    private void withdrawIsRefused(long phno, String reason) {
        bank.stubFor(WireMock.put(urlPathEqualTo("/bank/withdrawByphno")).withQueryParam("phno", equalTo("" + phno))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "text/plain").withBody(reason)));
    }

    private void transferSucceeds() {
        bank.stubFor(WireMock.post(urlPathEqualTo("/bank/transfer"))
                .willReturn(okForContentType("text/plain", "Transfer Successful")));
    }

    private void transferIsRefused(String reason) {
        bank.stubFor(WireMock.post(urlPathEqualTo("/bank/transfer"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "text/plain").withBody(reason)));
    }

    /** Every request the fake bank has received, oldest first, like "PUT /bank/withdrawByphno?phno=1&balance=250.0". */
    private List<String> bankCalls() {
        List<ServeEvent> events = new ArrayList<>(bank.getAllServeEvents());   // newest first
        Collections.reverse(events);
        return events.stream().map(e -> e.getRequest().getMethod() + " " + e.getRequest().getUrl()).toList();
    }

    /** The JSON body of every POST /bank/transfer the fake bank has received, oldest first. */
    private List<String> transferRequestBodies() {
        List<ServeEvent> events = new ArrayList<>(bank.getAllServeEvents());
        Collections.reverse(events);
        return events.stream()
                .filter(e -> e.getRequest().getUrl().equals("/bank/transfer"))
                .map(e -> e.getRequest().getBodyAsString())
                .toList();
    }

    private static String withdraw(long phno, String amount) {
        return "PUT /bank/withdrawByphno?phno=" + phno + "&balance=" + amount;
    }

    // ============ the app under test ============

    private String login(long phno) throws Exception {
        String body = mockMvc.perform(loginRequest(phno, PIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private static MockHttpServletRequestBuilder loginRequest(long phno, String pin) {
        return post("/phonepe/login").contentType(MediaType.APPLICATION_JSON).content("{\"phno\":" + phno + ",\"pin\":\"" + pin + "\"}");
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder send(String token, long receiver, String amount) {
        return as(token, post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"receiverPhno\":" + receiver + ",\"amount\":" + amount + "}");
    }

    private List<Transaction> storedTransactions() {
        return transactionRepository.findAll();
    }

    // ============ login and sessions ============

    @Test
    void everyCallToTheBank_carriesTheConfiguredServiceKey() throws Exception {
        // Proves the key is really wired through (bank.service.api-key -> BankGateway -> the header), not just
        // configured and silently unused - the Bank app now rejects any call missing this header. Login itself no
        // longer needs it (POST /bank/login is public - the PIN check IS the credential), so profile is used here
        // to exercise a call that still requires it.
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        String token = login(ASHA);

        mockMvc.perform(as(token, get("/phonepe/profile"))).andExpect(status().isOk());

        bank.verify(getRequestedFor(urlPathEqualTo("/bank/displayuser")).withHeader("X-Service-Key", equalTo("test-service-key")));
    }

    @Test
    void login_thenProfile_showsTheBanksData_withoutTheAadhaarNumber() throws Exception {
        bankHasUser(ASHA, "KUMAR CHARAN", 1000.0);
        String token = login(ASHA);

        mockMvc.perform(as(token, get("/phonepe/profile")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KUMAR CHARAN"))
                .andExpect(jsonPath("$.phno").value(ASHA))
                .andExpect(jsonPath("$.balance").value(1000.0))
                .andExpect(jsonPath("$.aadharNumber").doesNotExist());
    }

    @Test
    void everythingExceptLoginNeedsAToken() throws Exception {
        mockMvc.perform(get("/phonepe/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/phonepe/balance")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/phonepe/transactions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/phonepe/sendmoney").contentType(MediaType.APPLICATION_JSON)
                .content("{\"receiverPhno\":9123456789,\"amount\":10}")).andExpect(status().isUnauthorized());
        mockMvc.perform(as("made-up-token", get("/phonepe/profile"))).andExpect(status().isUnauthorized());

        assertEquals(List.of(), bankCalls(), "an unauthenticated caller must never make this service call the bank");
    }

    // The bank keeps this deliberately generic (same status/message for "no such phone" and "wrong PIN"), so an
    // attacker cannot tell the two apart; PhonepayService must relay that as-is, not narrow it to a 404 the way
    // it used to when login only ever looked the phone number up (which itself used to leak who was registered).
    @Test
    void login_wrongPinOrUnknownUser_is401_notA500() throws Exception {
        bankLoginFails(401, "Invalid phone number or PIN");

        mockMvc.perform(loginRequest(ASHA, "0000"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid phone number or PIN"));

        assertEquals(0, sessionRepository.count(), "no session for a rejected login");
    }

    @Test
    void login_accountLocked_is423() throws Exception {
        bankLoginFails(423, "Too many failed attempts. Try again after 2026-09-24T10:15:00Z.");

        mockMvc.perform(loginRequest(ASHA, PIN))
                .andExpect(status().isLocked())
                .andExpect(content().string("Too many failed attempts. Try again after 2026-09-24T10:15:00Z."));

        assertEquals(0, sessionRepository.count(), "no session for a locked account");
    }

    @Test
    void login_whenTheBankIsDown_is503() throws Exception {
        bank.stubFor(WireMock.post(urlPathEqualTo("/bank/login")).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(loginRequest(ASHA, PIN))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void login_invalidNumber_is400_andTheBankIsNotAsked() throws Exception {
        mockMvc.perform(loginRequest(5876543210L, PIN))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid mobile number"));

        assertEquals(List.of(), bankCalls());
    }

    @Test
    void theTokenIsNeverStoredInTheDatabase() throws Exception {
        bankHasUser(ASHA, "KUMAR CHARAN", 1000.0);
        String token = login(ASHA);

        UserSession stored = sessionRepository.findAll().get(0);

        assertNotEquals(token, stored.getTokenHash());
        assertEquals(64, stored.getTokenHash().length());
    }

    @Test
    void logout_endsTheSession() throws Exception {
        bankHasUser(ASHA, "KUMAR CHARAN", 1000.0);
        String token = login(ASHA);

        mockMvc.perform(as(token, post("/phonepe/logout"))).andExpect(status().isNoContent());

        mockMvc.perform(as(token, get("/phonepe/profile"))).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredSession_isRefused() throws Exception {
        bankHasUser(ASHA, "KUMAR CHARAN", 1000.0);
        String token = login(ASHA);
        UserSession session = sessionRepository.findAll().get(0);
        session.setExpiresAt(Instant.now().minusSeconds(1));
        sessionRepository.save(session);

        mockMvc.perform(as(token, get("/phonepe/profile")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Session expired. Please login again."));
    }

    // ============ two people at the same time: the old "latest login wins" bug ============

    @Test
    void twoPeopleLoggedInAtOnce_eachActsAsThemselves() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        bankHasUser(RAVI, "RAVI SHARMA", 2000.0);
        bankHasUser(MEENA, "MEENA RAO", 3000.0);
        transferSucceeds();
        String ashaToken = login(ASHA);
        String raviToken = login(RAVI);   // the LAST person to log in; the old code made everybody act as them

        assertNotEquals(ashaToken, raviToken);
        mockMvc.perform(as(ashaToken, get("/phonepe/profile"))).andExpect(jsonPath("$.name").value("ASHA KUMAR"));
        mockMvc.perform(as(raviToken, get("/phonepe/profile"))).andExpect(jsonPath("$.name").value("RAVI SHARMA"));

        bank.resetRequests();
        mockMvc.perform(send(ashaToken, MEENA, "100")).andExpect(status().isOk());

        // the money came out of ASHA's account, not out of RAVI's, even though RAVI logged in last
        assertEquals(List.of(TRANSFER), bankCalls());
        String body = transferRequestBodies().get(0);
        assertEquals(ASHA, ((Number) JsonPath.read(body, "$.payerPhno")).longValue(), body);
        assertEquals(ASHA, storedTransactions().get(0).getPhno());
    }

    // ============ sending money ============

    @Test
    void sendMoney_takesFromThePayerThenGivesToTheReceiver_andRecordsIt() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        transferSucceeds();
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(send(token, RAVI, "250"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(100000))
                .andExpect(jsonPath("$.direction").value("DEBIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(250.0))
                .andExpect(jsonPath("$.receiverPhno").value(RAVI));

        assertEquals(List.of(TRANSFER), bankCalls());
        String body = transferRequestBodies().get(0);
        assertEquals(ASHA, ((Number) JsonPath.read(body, "$.payerPhno")).longValue(), body);
        assertEquals(RAVI, ((Number) JsonPath.read(body, "$.receiverPhno")).longValue(), body);
        assertEquals("phonepe-100000", JsonPath.read(body, "$.idempotencyKey"));
        Transaction stored = storedTransactions().get(0);
        assertEquals(TransactionStatus.COMPLETED, stored.getStatus());
        assertEquals("Transfer", stored.getMode());
        assertEquals(0, stored.getAmount().compareTo(new java.math.BigDecimal("250")));
    }

    @Test
    void sendMoney_storesAndReturnsTheNote() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        transferSucceeds();
        String token = login(ASHA);

        mockMvc.perform(as(token, post("/phonepe/sendmoney")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverPhno\":" + RAVI + ",\"amount\":250,\"note\":\"rent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("rent"));

        assertEquals("rent", storedTransactions().get(0).getNote());

        mockMvc.perform(as(token, get("/phonepe/transactions")))
                .andExpect(jsonPath("$.content[0].note").value("rent"));
    }

    @Test
    void sendMoney_toAnUnknownNumber_neverTakesTheMoney() throws Exception {
        // The bank's transfer is atomic: if the receiver does not exist, the whole call fails with nothing moved.
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        transferIsRefused("Receiver not found");
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(send(token, RAVI, "250"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("User not found"));

        assertEquals(List.of(TRANSFER), bankCalls(), "no money may move");
        assertEquals(TransactionStatus.FAILED, storedTransactions().get(0).getStatus());
    }

    @Test
    void sendMoney_toYourself_isRefusedBeforeTheBankIsAsked() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(send(token, ASHA, "10"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("You cannot send money to yourself"));

        assertEquals(List.of(), bankCalls());
    }

    @Test
    void sendMoney_invalidAmounts_areRefused() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(send(token, RAVI, "0")).andExpect(status().isBadRequest()).andExpect(content().string("Amount too low"));
        mockMvc.perform(send(token, RAVI, "-50")).andExpect(status().isBadRequest());
        mockMvc.perform(send(token, RAVI, "1.234")).andExpect(status().isBadRequest());

        assertEquals(List.of(), bankCalls());
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void sendMoney_insufficientFunds_isRefused_andRecordedAsFailed() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 10.0);
        transferIsRefused("Insufficient Funds");
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(send(token, RAVI, "250"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));

        assertEquals(List.of(TRANSFER), bankCalls());
        assertEquals(TransactionStatus.FAILED, storedTransactions().get(0).getStatus());
    }

    @Test
    void sendMoney_bankStopsAnsweringDuringTheTransfer_nothingIsGuessed() throws Exception {
        // The bank may or may not have completed the transfer. Guessing either way could create or destroy money.
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        bank.stubFor(WireMock.post(urlPathEqualTo("/bank/transfer")).willReturn(aResponse().withStatus(200).withFixedDelay(4000)));
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(send(token, RAVI, "250"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("could not confirm")));

        assertEquals(3, transferRequestBodies().size(), "every retry reused the same idempotency key, so 3 attempts can never move money twice");
        assertEquals(TransactionStatus.NEEDS_RECONCILIATION, storedTransactions().get(0).getStatus());
    }

    @Test
    void sendMoney_connectionDropsDuringTheTransfer_nothingIsGuessed() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        bank.stubFor(WireMock.post(urlPathEqualTo("/bank/transfer"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(send(token, RAVI, "250"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("could not confirm")));

        assertEquals(3, transferRequestBodies().size());
        assertEquals(TransactionStatus.NEEDS_RECONCILIATION, storedTransactions().get(0).getStatus());
    }

    // ============ make a payment ============

    @Test
    void makePayment_takesTheMoney_andRecordsAPaymentWithNoReceiver() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        withdrawSucceeds(ASHA);
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(as(token, post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":99.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("Payment"))
                .andExpect(jsonPath("$.receiverPhno").doesNotExist())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(List.of(withdraw(ASHA, "99.5")), bankCalls());
    }

    @Test
    void makePayment_storesAndReturnsTheNote() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        withdrawSucceeds(ASHA);
        String token = login(ASHA);

        mockMvc.perform(as(token, post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":99.5,\"note\":\"movie tickets\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("movie tickets"));

        assertEquals("movie tickets", storedTransactions().get(0).getNote());
    }

    @Test
    void makePayment_insufficientFunds_isRefused() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1.0);
        withdrawIsRefused(ASHA, "Insufficient Funds");
        String token = login(ASHA);

        mockMvc.perform(as(token, post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient Funds"));
    }

    @Test
    void makePayment_connectionDropsDuringTheWithdrawal_theWithdrawalIsSentOnlyOnce() throws Exception {
        // The bank's withdraw subtracts every time it is called and has no idempotency key, so unlike transfer(),
        // debit() must never retry - an automatic re-send here could take the money twice.
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        bank.stubFor(WireMock.put(urlPathEqualTo("/bank/withdrawByphno"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        String token = login(ASHA);
        bank.resetRequests();

        mockMvc.perform(as(token, post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":250}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("could not confirm")));

        assertEquals(List.of(withdraw(ASHA, "250.0")), bankCalls(), "one withdrawal attempt, no re-send");
        assertEquals(TransactionStatus.NEEDS_RECONCILIATION, storedTransactions().get(0).getStatus());
    }

    // ============ history: people only ever see their own money ============

    @Test
    void afterManyPayments_profileAndHistoryStillWork() throws Exception {
        // The old /profile threw an exception as soon as a user had made two payments.
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        transferSucceeds();
        String token = login(ASHA);
        for (String amount : List.of("10", "20", "30")) {
            mockMvc.perform(send(token, RAVI, amount)).andExpect(status().isOk());
        }

        mockMvc.perform(as(token, get("/phonepe/profile"))).andExpect(status().isOk());
        mockMvc.perform(as(token, get("/phonepe/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].transactionId").value(100002))   // newest first
                .andExpect(jsonPath("$.content[2].transactionId").value(100000));
    }

    @Test
    void transactions_arePaginated() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        transferSucceeds();
        String token = login(ASHA);
        for (String amount : List.of("10", "20", "30")) {
            mockMvc.perform(send(token, RAVI, amount)).andExpect(status().isOk());
        }

        mockMvc.perform(as(token, get("/phonepe/transactions")).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].transactionId").value(100002));

        mockMvc.perform(as(token, get("/phonepe/transactions")).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transactionId").value(100000));
    }

    @Test
    void transactions_invalidPageOrSize_returns400() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        String token = login(ASHA);

        mockMvc.perform(as(token, get("/phonepe/transactions")).param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(as(token, get("/phonepe/transactions")).param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(as(token, get("/phonepe/transactions")).param("size", "1000")).andExpect(status().isBadRequest());
    }

    @Test
    void transactions_canBeFilteredByDateRange() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        withdrawSucceeds(ASHA);
        String token = login(ASHA);
        mockMvc.perform(as(token, post("/phonepe/makepayment")).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"))
                .andExpect(status().isOk());

        // backdate it well outside any date range a real caller would use "now"
        Transaction stored = transactionRepository.findAll().get(0);
        stored.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        transactionRepository.save(stored);

        mockMvc.perform(as(token, get("/phonepe/transactions")).param("from", Instant.now().minusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(as(token, get("/phonepe/transactions")).param("from", "2019-01-01T00:00:00Z").param("to", "2021-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void transactions_fromAfterTo_returns400() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        String token = login(ASHA);

        mockMvc.perform(as(token, get("/phonepe/transactions"))
                        .param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("'from' must not be after 'to'."));
    }

    @Test
    void history_isPrivate_theReceiverSeesACredit_aStrangerSeesNothing() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        bankHasUser(RAVI, "RAVI SHARMA", 0.0);
        bankHasUser(MEENA, "MEENA RAO", 0.0);
        transferSucceeds();
        String ashaToken = login(ASHA);
        String raviToken = login(RAVI);
        String meenaToken = login(MEENA);
        mockMvc.perform(send(ashaToken, RAVI, "250")).andExpect(status().isOk());

        mockMvc.perform(as(ashaToken, get("/phonepe/transactions")))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].direction").value("DEBIT"));
        mockMvc.perform(as(raviToken, get("/phonepe/transactions")))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].direction").value("CREDIT"));
        mockMvc.perform(as(meenaToken, get("/phonepe/transactions")))
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(as(ashaToken, get("/phonepe/transactions/100000"))).andExpect(status().isOk());
        mockMvc.perform(as(raviToken, get("/phonepe/transactions/100000"))).andExpect(status().isOk());
        mockMvc.perform(as(meenaToken, get("/phonepe/transactions/100000")))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Transaction not found"));
    }

    @Test
    void theOldOpenEndpointsAreGone() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 1000.0);
        String token = login(ASHA);

        for (String path : List.of("/phonepe/all", "/phonepe/transactionByphno", "/phonepe/transactionbyacno", "/phonepe/checkBalance")) {
            mockMvc.perform(as(token, get(path).param("phno", "9123456789"))).andExpect(status().isNotFound());
        }
    }

    // ============ many payments at the same moment ============

    @Test
    void simultaneousPayments_getUniqueTransactionNumbers_andEachMovesMoneyExactlyOnce() throws Exception {
        bankHasUser(ASHA, "ASHA KUMAR", 100000.0);
        transferSucceeds();
        String token = login(ASHA);
        bank.resetRequests();

        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(requests);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                Callable<Integer> call = () -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(send(token, RAVI, "10")).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long succeeded = statuses.stream().filter(s -> s == 200).count();
        assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 409), "unexpected statuses: " + statuses);
        assertTrue(succeeded >= 1, "at least one payment must win: " + statuses);
        List<Transaction> stored = storedTransactions();
        assertEquals(succeeded, stored.size(), "one row per successful payment, none for the refused ones");
        assertEquals(succeeded, stored.stream().map(Transaction::getTransactionId).distinct().count(), "every transaction number is unique");
        assertTrue(stored.stream().allMatch(t -> t.getStatus() == TransactionStatus.COMPLETED));
        // each successful payment moved money exactly once
        bank.verify((int) succeeded, postRequestedFor(urlPathEqualTo("/bank/transfer")));
    }

    // ============ operations ============

    @Test
    void actuator_onlyExposesHealth_notEnvironmentOrHeapDumps() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }
}
