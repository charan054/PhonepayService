package com.example.phonepayservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="transaction", indexes = {
        @Index(name = "idx_transaction_phno", columnList = "phno"),
        @Index(name = "idx_transaction_recieverno", columnList = "recieverno")
})
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
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;
    private String failureReason;
    private Instant createdAt;
    // What the payer said this was for, e.g. "rent" or "movie tickets". Optional; null for older rows and for
    // any payment made without one.
    @Column(length = 140)
    private String note;
}
