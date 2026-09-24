package com.example.phonepayservice.dto;

import lombok.Data;

import java.math.BigDecimal;

// What the bank's /bank/displayuser returns. Fields we do not need (such as the Aadhaar number) are deliberately not
// declared, so they are ignored and never travel any further. balance is BigDecimal, not double, because the bank
// already sends it as an exact decimal - going through double here would only reintroduce the precision loss that
// was already fixed on the amount-sent side (see BankGateway.withdraw/deposit).
@Data
public class BankUser {
    private int userId;
    private long acno;
    private String name;
    private long phno;
    private BigDecimal balance;
}
