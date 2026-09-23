package com.example.phonepayservice.dto;

import lombok.Data;

// What the bank's POST /bank/login returns. Only the name is needed here: the token/expiresAt are the BANK's
// own session, irrelevant to PhonepayService, which issues its own.
@Data
public class BankLoginResult {
    private String name;
}
