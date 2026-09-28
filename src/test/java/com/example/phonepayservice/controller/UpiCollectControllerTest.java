package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.ClockConfig;
import com.example.phonepayservice.configuration.WebConfig;
import com.example.phonepayservice.entity.UpiCollectRequest;
import com.example.phonepayservice.entity.UpiCollectRequestStatus;
import com.example.phonepayservice.exception.UserNotRegisteredException;
import com.example.phonepayservice.service.SessionService;
import com.example.phonepayservice.service.UpiCollectRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "test" profile: internal.service.api-key=test-internal-service-key (see application-test.properties), so
// these tests never depend on INTERNAL_SERVICE_API_KEY being set on the machine.
@WebMvcTest(UpiCollectController.class)
@Import({WebConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class UpiCollectControllerTest {

    private static final String VALID_SERVICE_KEY = "test-internal-service-key";
    private static final long PAYER = 9123456789L;
    private static final String TOKEN = "good-token";
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UpiCollectRequestService upiCollectRequestService;
    @MockitoBean
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        when(sessionService.authenticate(TOKEN)).thenReturn(PAYER);
        when(sessionService.authenticate(null)).thenThrow(new UserNotRegisteredException("Login required"));
    }

    private UpiCollectRequest pending(long id) {
        UpiCollectRequest r = new UpiCollectRequest();
        r.setId(id);
        r.setMerchantReference("OrderService-42");
        r.setPayerPhno(PAYER);
        r.setAmount(new BigDecimal("250.00"));
        r.setStatus(UpiCollectRequestStatus.PENDING);
        r.setCreatedAt(NOW);
        r.setExpiresAt(NOW.plusSeconds(240));
        return r;
    }

    // ---------- merchant endpoints: X-Service-Key required ----------

    @Test
    void createCollectRequest_withoutServiceKey_returns401() throws Exception {
        mockMvc.perform(post("/phonepe/upi/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"OrderService-42\",\"upiId\":\"" + PAYER + "@charanpe\",\"amount\":250}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createCollectRequest_withWrongServiceKey_returns401() throws Exception {
        mockMvc.perform(post("/phonepe/upi/collect")
                        .header("X-Service-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"OrderService-42\",\"upiId\":\"" + PAYER + "@charanpe\",\"amount\":250}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createCollectRequest_withValidServiceKey_succeeds() throws Exception {
        when(upiCollectRequestService.create(eq("OrderService-42"), eq(PAYER + "@charanpe"), any(), any()))
                .thenReturn(pending(1));

        mockMvc.perform(post("/phonepe/upi/collect")
                        .header("X-Service-Key", VALID_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"OrderService-42\",\"upiId\":\"" + PAYER + "@charanpe\",\"amount\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payerUpiId").value(PAYER + "@charanpe"));
    }

    @Test
    void getCollectRequestByReference_withoutServiceKey_returns401() throws Exception {
        mockMvc.perform(get("/phonepe/upi/collect/OrderService-42")).andExpect(status().isUnauthorized());
    }

    @Test
    void getCollectRequestByReference_withValidServiceKey_succeeds() throws Exception {
        when(upiCollectRequestService.getByMerchantReference("OrderService-42")).thenReturn(pending(1));

        mockMvc.perform(get("/phonepe/upi/collect/OrderService-42").header("X-Service-Key", VALID_SERVICE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantReference").value("OrderService-42"));
    }

    // A merchant's X-Service-Key must not double as a buyer's Bearer token on the buyer-facing endpoints - the
    // two are separate trust boundaries even though they happen to both gate the same feature.
    @Test
    void serviceKeyAloneDoesNotAuthenticateTheBuyerFacingEndpoints() throws Exception {
        mockMvc.perform(get("/phonepe/upi/requests").header("X-Service-Key", VALID_SERVICE_KEY))
                .andExpect(status().isUnauthorized());
    }

    // ---------- buyer endpoints: Bearer session required ----------

    @Test
    void myRequests_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/phonepe/upi/requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void myRequests_withValidToken_succeeds() throws Exception {
        when(upiCollectRequestService.listPendingFor(PAYER)).thenReturn(java.util.List.of(pending(1)));

        mockMvc.perform(get("/phonepe/upi/requests").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void approve_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/phonepe/upi/requests/1/approve")).andExpect(status().isUnauthorized());
    }

    @Test
    void approve_withValidToken_succeeds() throws Exception {
        UpiCollectRequest approved = pending(1);
        approved.setStatus(UpiCollectRequestStatus.APPROVED);
        when(upiCollectRequestService.approve(PAYER, 1)).thenReturn(approved);

        mockMvc.perform(post("/phonepe/upi/requests/1/approve").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void decline_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/phonepe/upi/requests/1/decline")).andExpect(status().isUnauthorized());
    }

    @Test
    void decline_withValidToken_succeeds() throws Exception {
        UpiCollectRequest declined = pending(1);
        declined.setStatus(UpiCollectRequestStatus.DECLINED);
        when(upiCollectRequestService.decline(PAYER, 1)).thenReturn(declined);

        mockMvc.perform(post("/phonepe/upi/requests/1/decline").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
    }
}
