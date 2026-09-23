package com.example.phonepayservice.dto;

import java.math.BigDecimal;

/**
 * The body sent to the bank's POST /bank/transfer. idempotencyKey lets a retry (after a timeout, say) be sent
 * safely: the bank returns the original result instead of moving the money again.
 */
public record BankTransferRequest(long payerPhno, long receiverPhno, BigDecimal amount, String idempotencyKey) {
}
