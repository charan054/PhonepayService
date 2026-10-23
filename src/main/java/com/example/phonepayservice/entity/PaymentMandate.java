package com.example.phonepayservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A customer's standing permission for the shop to charge their PhonePe account without asking each time - for subscription
 * orders. The customer sets it up once, confirming with their PIN, with hard limits: the most for one charge, the most in a
 * calendar month, and an end date. They can revoke it at any moment. The shop can only charge through it (never beyond the
 * limits, never after it ends), and every charge is an ordinary payment in the customer's history.
 */
@Data
@Entity
@Table(name = "payment_mandate", indexes = @Index(name = "idx_payment_mandate_payer", columnList = "payer_phno"))
public class PaymentMandate {
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "payer_phno")
    private long payerPhno;
    // Who may charge it. Only one merchant exists today; kept so a second one later needs no schema change.
    @Column(length = 40)
    private String merchant;
    @Column(precision = 19, scale = 2)
    private BigDecimal perChargeLimit;
    @Column(precision = 19, scale = 2)
    private BigDecimal monthlyLimit;
    // What has been charged in chargeMonth (YYYY-MM, Indian time); the total starts again from zero when the month changes.
    @Column(precision = 19, scale = 2)
    private BigDecimal chargedThisMonth = BigDecimal.ZERO;
    @Column(length = 7)
    private String chargeMonth;
    private Instant validUntil;
    // A plain string, not a native MySQL ENUM, so a new state later is only a code change.
    @Column(length = 10, nullable = false)
    private String status = ACTIVE;
    private Instant createdAt;
    private Instant lastChargeAt;
    private Instant revokedAt;
    // Two charges at the same moment must not both squeeze under the monthly limit: a stale save is refused and retried.
    @Version
    private long version;
}
