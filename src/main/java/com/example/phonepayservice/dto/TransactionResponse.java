package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A transaction as one particular person sees it. "direction" says whether it took money from them (DEBIT)
 * or gave money to them (CREDIT). Internal details such as failure reasons are never exposed.
 */
public record TransactionResponse(long transactionId, String mode, String direction, long payerPhno,
                                  Long receiverPhno, BigDecimal amount, String status, Instant createdAt, String note) {

    public static TransactionResponse from(Transaction t, long viewer) {
        Long receiver = t.getReceiverPhno() == null || t.getReceiverPhno() == 0 ? null : t.getReceiverPhno();
        return new TransactionResponse(
                t.getTransactionId(),
                t.getMode(),
                t.getPhno() == viewer ? "DEBIT" : "CREDIT",
                t.getPhno(),
                receiver,
                t.getAmount(),
                t.getStatus() == null ? "COMPLETED" : t.getStatus().name(),   // rows from before statuses existed
                t.getCreatedAt(),
                t.getNote());
    }
}
