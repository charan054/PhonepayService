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

// A merchant's request to collect a payment from a PhonepayService user by their UPI ID - created by a trusted
// backend (X-Service-Key, see UpiCollectController), approved or declined only by the payer themselves (their
// own Bearer session), the same shape MoneyRequest already has for a peer-to-peer ask, but initiated by a
// service instead of a person. Approving one moves money the exact same way a direct makePayment() does (see
// UpiCollectRequestService.approve()).
@Entity
@Table(name = "upi_collect_request", indexes = {
        @Index(name = "idx_upi_collect_request_payer_phno", columnList = "payer_phno"),
        @Index(name = "idx_upi_collect_request_merchant_reference", columnList = "merchant_reference", unique = true)
})
@Data
public class UpiCollectRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    // The calling service's own identifier for what this payment is for (e.g. an OrderService order id) -
    // unique, so a retried create() call is idempotent instead of raising a second request for the same order.
    @Column(name = "merchant_reference", unique = true, length = 100)
    private String merchantReference;
    @Column(name = "payer_phno")
    private long payerPhno;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(length = 140)
    private String note;
    // columnDefinition pins this to a plain VARCHAR rather than a native MySQL ENUM sized to whatever constants
    // exist today - see OrderService's Cart.status for the exact "Data truncated" failure this avoids if a
    // status is ever added later.
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)")
    private UpiCollectRequestStatus status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant resolvedAt;
    // Set only once APPROVED; null while PENDING and stays null if DECLINED/EXPIRED.
    private Long resultTransactionId;
}
