package com.example.phonepayservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

// One person asking another for money. Approving it moves real money via PhonepeService.sendMoney(), using
// "money-request-<id>" as the idempotency key, so a retried approve() can never transfer twice.
@Entity
@Table(name = "money_request", indexes = {
        @Index(name = "idx_money_request_requester_phno", columnList = "requester_phno"),
        @Index(name = "idx_money_request_payer_phno", columnList = "payer_phno")
})
@Data
public class MoneyRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "requester_phno")
    private long requesterPhno;
    @Column(name = "payer_phno")
    private long payerPhno;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(length = 140)
    private String note;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MoneyRequestStatus status;
    private Instant createdAt;
    private Instant resolvedAt;
    // Set only once approved; null while PENDING and stays null if DECLINED.
    private Long resultingTransactionId;
}
