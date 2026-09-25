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

// A standing instruction to pay the same payee the same amount every intervalDays, until paused or cancelled.
// intervalDays rather than a WEEKLY/MONTHLY enum deliberately: calendar-month arithmetic (Jan 31 + 1 month?) is
// its own source of bugs, and a plain day count is unambiguous for every interval this app needs to support.
@Entity
@Table(name = "recurring_payment", indexes = {
        @Index(name = "idx_recurring_payment_owner_phno", columnList = "owner_phno"),
        // what the scheduler queries by: every ACTIVE row whose next_run_at has arrived
        @Index(name = "idx_recurring_payment_status_next_run", columnList = "status, next_run_at")
})
@Data
public class RecurringPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    // the payer
    @Column(name = "owner_phno")
    private long ownerPhno;
    @Column(name = "payee_phno")
    private long payeePhno;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(length = 140)
    private String note;
    private int intervalDays;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringPaymentStatus status;
    private Instant createdAt;
    private Instant nextRunAt;
    // null until the first successful run
    private Instant lastRunAt;
}
