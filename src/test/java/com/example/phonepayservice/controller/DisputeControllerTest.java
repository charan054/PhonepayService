package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.WebConfig;
import com.example.phonepayservice.configuration.ClockConfig;
import com.example.phonepayservice.dto.DisputeDtos.DisputeResponse;
import com.example.phonepayservice.service.DisputeService;
import com.example.phonepayservice.service.SessionService;
import com.example.phonepayservice.exception.UserNotRegisteredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DisputeController.class)
@Import({WebConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class DisputeControllerTest {
    private static final String VALID_SERVICE_KEY = "test-internal-service-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DisputeService disputes;
    @MockitoBean
    private SessionService sessionService;

    private static DisputeResponse sample(String status) {
        return new DisputeResponse(7, 100000L, 9876543210L, "wrong", status, null, null, null, null);
    }

    @BeforeEach
    void loginSetup() {
        when(sessionService.authenticate("good-token")).thenReturn(9876543210L);
        when(sessionService.authenticate(null)).thenThrow(new UserNotRegisteredException("Login required"));
    }

    @Test
    void raisingWithoutALoginIsRefused() throws Exception {
        mockMvc.perform(post("/phonepe/disputes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":100000,\"reason\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/phonepe/disputes")).andExpect(status().isUnauthorized());
        verifyNoInteractions(disputes);
    }

    @Test
    void aLoggedInCustomerRaisesADisputeAsThemselvesNotAsAnyonePassedInTheBody() throws Exception {
        when(disputes.raise(9876543210L, 100000L, "wrong")).thenReturn(sample("OPEN"));

        mockMvc.perform(post("/phonepe/disputes").header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":100000,\"reason\":\"wrong\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void aBlankReasonIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/phonepe/disputes").header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":100000,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(disputes);
    }

    @Test
    void theStaffQueueAndResolveNeedTheServiceKey() throws Exception {
        mockMvc.perform(get("/phonepe/merchant/disputes")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/phonepe/merchant/disputes/7/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(disputes);
    }

    @Test
    void staffSeeTheOpenQueue() throws Exception {
        when(disputes.openQueue()).thenReturn(List.of(sample("OPEN")));

        mockMvc.perform(get("/phonepe/merchant/disputes").header("X-Service-Key", VALID_SERVICE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    void staffResolveAPassesTheDecisionAndNoteThrough() throws Exception {
        when(disputes.resolve(7, false, "delivered")).thenReturn(sample("REJECTED"));

        mockMvc.perform(post("/phonepe/merchant/disputes/7/resolve").header("X-Service-Key", VALID_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":false,\"note\":\"delivered\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        verify(disputes).resolve(7, false, "delivered");
    }
}
