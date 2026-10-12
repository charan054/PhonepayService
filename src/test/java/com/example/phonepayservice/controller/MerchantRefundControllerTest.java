package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.ClockConfig;
import com.example.phonepayservice.configuration.WebConfig;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.entity.TransactionStatus;
import com.example.phonepayservice.service.PhonepeService;
import com.example.phonepayservice.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MerchantRefundController.class)
@Import({WebConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class MerchantRefundControllerTest {
    private static final String VALID_SERVICE_KEY = "test-internal-service-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhonepeService phonepeService;
    @MockitoBean
    private SessionService sessionService;

    @Test
    void refundWithoutTheServiceKeyIsRefusedAndNothingMoves() throws Exception {
        mockMvc.perform(post("/phonepe/merchant/transactions/100000/refund")).andExpect(status().isUnauthorized());
        verifyNoInteractions(phonepeService);
    }

    @Test
    void refundWithTheServiceKeyPaysTheOriginalPayerBack() throws Exception {
        Transaction refund = new Transaction();
        refund.setTransactionId(100001);
        refund.setPhno(9123456789L);
        refund.setMode("Refund");
        refund.setAmount(new BigDecimal("120.00"));
        refund.setStatus(TransactionStatus.COMPLETED);
        when(phonepeService.merchantRefund(eq(100000L), any(), any())).thenReturn(refund);

        mockMvc.perform(post("/phonepe/merchant/transactions/100000/refund")
                        .header("X-Service-Key", VALID_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"settle-1\"}"))
                .andExpect(status().isOk());

        verify(phonepeService).merchantRefund(100000L, null, "settle-1");
    }
}
