package com.example.phonepayservice.entity;

/** Lifecycle of a recurring payment. Only ACTIVE ones are ever picked up by the scheduler. */
public enum RecurringPaymentStatus {
    /** Due payments run automatically. */
    ACTIVE,
    /** Paused by the owner, or automatically after a failed run - the schedule is frozen until resumed. */
    PAUSED,
    /** Cancelled by the owner. Kept (not deleted) so its history stays visible. */
    CANCELLED
}
