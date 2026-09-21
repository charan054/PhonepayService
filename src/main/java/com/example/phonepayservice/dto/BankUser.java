package com.example.phonepayservice.dto;

import lombok.Data;

// What the bank's /bank/displayuser returns. Fields we do not need (such as the Aadhaar number) are deliberately not
// declared, so they are ignored and never travel any further.
@Data
public class BankUser {
    private int userId;
    private long acno;
    private String name;
    private long phno;
    private double balance;
}
