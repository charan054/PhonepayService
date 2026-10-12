package com.example.phonepayservice.controller;

import com.example.phonepayservice.dto.RefundRequest;
import com.example.phonepayservice.dto.TransactionResponse;
import com.example.phonepayservice.service.PhonepeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Merchant-only (X-Service-Key, see ServiceKeyInterceptor): pays a completed payment back to the account that made
 * it without that buyer's session. Same rules and same idempotency behaviour as the buyer's own refund endpoint.
 */
@RestController
@RequestMapping("/phonepe/merchant")
public class MerchantRefundController {
    private final PhonepeService phonepeService;

    public MerchantRefundController(PhonepeService phonepeService) {
        this.phonepeService = phonepeService;
    }

    @PostMapping("/transactions/{transactionId}/refund")
    public TransactionResponse refund(@PathVariable long transactionId, @RequestBody(required = false) @Valid RefundRequest request) {
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        BigDecimal amount = request == null ? null : request.amount();
        var refund = phonepeService.merchantRefund(transactionId, amount, idempotencyKey);
        return TransactionResponse.from(refund, refund.getPhno());
    }
}
