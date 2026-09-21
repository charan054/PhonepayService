package com.example.phonepayservice.entity;

/**
 * Lifecycle of a payment. It is recorded BEFORE any money moves, so a crash half way still leaves a trace.
 */
public enum TransactionStatus {
    /** Recorded, money movement not finished yet. */
    PENDING,
    /** All money moved as intended. */
    COMPLETED,
    /** Nothing moved, or the payer was refunded. */
    FAILED,
    /** Money may be in limbo (the bank did not confirm an operation, or a refund failed). A person must settle it. */
    NEEDS_RECONCILIATION
}
