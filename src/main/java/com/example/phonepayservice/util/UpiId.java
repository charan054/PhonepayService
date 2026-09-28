package com.example.phonepayservice.util;

import com.example.phonepayservice.exception.InvalidRequestException;

// Deterministic UPI ID for a PhonepayService user: "<phone number>@charanpe". Nothing is stored - it's always
// derivable from (and parseable back to) the phone number, so there's no separate table to keep in sync.
public final class UpiId {
    public static final String DOMAIN = "charanpe";

    private UpiId() {
    }

    public static String forPhno(long phno) {
        return phno + "@" + DOMAIN;
    }

    public static long toPhno(String upiId) {
        if (upiId == null || upiId.isBlank()) {
            throw new InvalidRequestException("A UPI ID is required");
        }
        String trimmed = upiId.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0 || !trimmed.substring(at + 1).equalsIgnoreCase(DOMAIN)) {
            throw new InvalidRequestException("Invalid UPI ID. Expected format: <phone number>@" + DOMAIN);
        }
        try {
            return Long.parseLong(trimmed.substring(0, at));
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Invalid UPI ID. Expected format: <phone number>@" + DOMAIN);
        }
    }
}
