package com.example.phonepayservice.entity;

/** Lifecycle of a request for money. Only ever moves PENDING -> APPROVED or PENDING -> DECLINED, never back. */
public enum MoneyRequestStatus {
    /** Waiting on the payer to act. */
    PENDING,
    /** The payer approved it and the resulting transfer completed. */
    APPROVED,
    /** The payer declined it. No money moved. */
    DECLINED
}
