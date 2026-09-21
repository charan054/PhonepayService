package com.example.phonepayservice.dto;

import java.math.BigDecimal;

public record ProfileResponse(long phno, String name, long acno, BigDecimal balance) {
}
