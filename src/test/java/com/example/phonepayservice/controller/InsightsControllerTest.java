package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.ClockConfig;
import com.example.phonepayservice.configuration.WebConfig;
import com.example.phonepayservice.dto.InsightsResponse;
import com.example.phonepayservice.exception.UserNotRegisteredException;
import com.example.phonepayservice.service.InsightsService;
import com.example.phonepayservice.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsightsController.class)
@Import({WebConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class InsightsControllerTest {
    private static final long CALLER = 9876543210L;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private InsightsService insights;
    @MockitoBean
    private SessionService sessionService;

    @BeforeEach
    void login() {
        when(sessionService.authenticate("good-token")).thenReturn(CALLER);
        when(sessionService.authenticate(null)).thenThrow(new UserNotRegisteredException("Login required"));
    }

    @Test
    void withoutALoginItIsRefused() throws Exception {
        mockMvc.perform(get("/phonepe/insights")).andExpect(status().isUnauthorized());
        verifyNoInteractions(insights);
    }

    @Test
    void theCallerGetsTheirOwnInsightsAndMonthsIsPassedThrough() throws Exception {
        when(insights.insights(CALLER, 3)).thenReturn(
                new InsightsResponse(List.of(), List.of(), new BigDecimal("12.50")));

        mockMvc.perform(get("/phonepe/insights?months=3").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageMonthlyNetSpend").value(12.5));
        verify(insights).insights(CALLER, 3);
    }

    @Test
    void monthsIsOptional() throws Exception {
        when(insights.insights(CALLER, null)).thenReturn(new InsightsResponse(List.of(), List.of(), BigDecimal.ZERO));

        mockMvc.perform(get("/phonepe/insights").header("Authorization", "Bearer good-token")).andExpect(status().isOk());
        verify(insights).insights(org.mockito.ArgumentMatchers.eq(CALLER), isNull());
    }
}
