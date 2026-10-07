package com.example.phonepayservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="transaction", indexes = {
        @Index(name = "idx_transaction_phno", columnList = "phno"),
        @Index(name = "idx_transaction_recieverno", columnList = "recieverno"),
        @Index(name = "idx_transaction_refund_of", columnList = "refund_of_transaction_id")
}, uniqueConstraints = @UniqueConstraint(name = "uq_transaction_phno_idempotency_key", columnNames = {"phno", "idempotency_key"}))
@Data
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(unique = true)
    private long transactionId;
    // the payer
    private long phno;
    private String mode;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    // null for payments that have no receiver. The column keeps its original (misspelled) name so existing data still lines up.
    @Column(name = "recieverno")
    private Long receiverPhno;
    // Every write path (record()/settle()/unresolved() in PhonepeService) always sets this before the row is
    // ever visible to a reader; enforced at the database too so a future write path can't leave it unset and
    // silently be treated as COMPLETED by TransactionResponse.from()/PhonepeService.visibleTo()'s legacy-row
    // fallback for a row that was never actually legacy.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;
    private String failureReason;
    private Instant createdAt;
    // What the payer said this was for, e.g. "rent" or "movie tickets". Optional; null for older rows and for
    // any payment made without one.
    @Column(length = 140)
    private String note;
    // Optional: lets a caller safely retry an identical sendmoney/makepayment request after a lost response
    // (network drop, client timeout) without risking a duplicate transfer. Scoped per payer, not globally
    // unique, so two different customers can never collide on the same key by coincidence. Null when the
    // caller didn't supply one - such a request gets no retry protection, exactly as before this field existed.
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    // Set only on a "Refund" row: the transactionId of the Payment it (partly or fully) reverses. A payment may
    // have several refund rows; PhonepeService.refund() guarantees under a row lock on the original payment that
    // their non-failed amounts never add up to more than it was for.
    @Column(name = "refund_of_transaction_id")
    private Long refundOfTransactionId;
}
