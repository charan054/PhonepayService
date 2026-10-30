package com.example.phonepayservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.Instant;

/**
 * A customer's complaint about one of their own completed payments ("I was charged for something I did not get").
 * One dispute per payment. Staff either approve it - which pays the whole payment back through the ordinary
 * merchant refund, so every refund rule still applies - or reject it with a note the customer can read.
 */
@Data
@Entity
@Table(name = "payment_dispute",
        uniqueConstraints = @UniqueConstraint(name = "uq_payment_dispute_transaction", columnNames = "transaction_id"),
        indexes = @Index(name = "idx_payment_dispute_phno", columnList = "phno"))
public class PaymentDispute {
    public static final String OPEN = "OPEN";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "transaction_id")
    private long transactionId;
    private long phno;
    @Column(length = 500)
    private String reason;
    @Column(length = 10, nullable = false)
    private String status = OPEN;
    // The explanation staff gave, shown to the customer; set when the dispute is resolved.
    @Column(length = 500)
    private String resolutionNote;
    // The refund payment an approval created.
    private Long refundTransactionId;
    private Instant createdAt;
    private Instant resolvedAt;
    // Two staff resolving the same dispute at once: the second save fails instead of overwriting the first.
    @Version
    private long version;
}
