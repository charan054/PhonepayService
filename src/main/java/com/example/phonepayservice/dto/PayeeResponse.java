package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.SavedPayee;

// payeeName is only ever populated right after saving, when the bank was just asked to confirm the account - the
// plain list of saved payees does not re-verify each one against the bank on every call, so it's always null there.
public record PayeeResponse(long payeePhno, String nickname, String payeeName) {

    public static PayeeResponse from(SavedPayee p) {
        return new PayeeResponse(p.getPayeePhno(), p.getNickname(), null);
    }

    public static PayeeResponse from(SavedPayee p, String payeeName) {
        return new PayeeResponse(p.getPayeePhno(), p.getNickname(), payeeName);
    }
}
