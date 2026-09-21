package com.example.phonepayservice.dto;

import java.math.BigDecimal;

public record BalanceResponse(long phno, BigDecimal balance) {
}
