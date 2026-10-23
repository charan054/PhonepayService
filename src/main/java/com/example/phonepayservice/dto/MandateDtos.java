package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.PaymentMandate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class MandateDtos {
    private MandateDtos() {
    }

    /** The customer sets up a mandate: limits, how long it lasts, and their PIN again to prove it is really them. */
    public record CreateRequest(
            @NotNull(message = "The most for one charge is required")
            @DecimalMin(value = "1.00", message = "The most for one charge must be at least 1")
            @Digits(integer = 9, fraction = 2, message = "Amount can have at most 2 decimal places")
            BigDecimal perChargeLimit,

            @NotNull(message = "The most per month is required")
            @DecimalMin(value = "1.00", message = "The most per month must be at least 1")
            @Digits(integer = 9, fraction = 2, message = "Amount can have at most 2 decimal places")
            BigDecimal monthlyLimit,

            @NotNull(message = "How many days it lasts is required")
            @Min(value = 1, message = "A mandate lasts at least 1 day")
            @Max(value = 365, message = "A mandate lasts at most 365 days")
            Integer validDays,

            @NotBlank(message = "PIN is required to set up a mandate")
            String pin) {
    }

    /** What the customer (and the shop, to check ownership) sees of a mandate. */
    public record View(long id, long payerPhno, String merchant, BigDecimal perChargeLimit, BigDecimal monthlyLimit,
                       BigDecimal chargedThisMonth, BigDecimal remainingThisMonth, Instant validUntil, String status,
                       Instant createdAt, Instant lastChargeAt) {
        public static View of(PaymentMandate m, BigDecimal usedThisMonth) {
            BigDecimal remaining = m.getMonthlyLimit().subtract(usedThisMonth).max(BigDecimal.ZERO);
            return new View(m.getId(), m.getPayerPhno(), m.getMerchant(), m.getPerChargeLimit(), m.getMonthlyLimit(),
                    usedThisMonth, remaining, m.getValidUntil(), m.getStatus(), m.getCreatedAt(), m.getLastChargeAt());
        }
    }

    /** The shop charges a mandate. The idempotency key makes a retry of the same charge a no-op instead of a second debit. */
    public record ChargeRequest(
            @NotNull(message = "Amount is required")
            @DecimalMin(value = "0.01", message = "Amount too low")
            @Digits(integer = 9, fraction = 2, message = "Amount can have at most 2 decimal places")
            BigDecimal amount,

            @NotBlank(message = "An idempotency key is required")
            @Size(max = 64, message = "The idempotency key can be at most 64 characters")
            String idempotencyKey,

            @Size(max = 140, message = "Note can be at most 140 characters")
            String note) {
    }
}
