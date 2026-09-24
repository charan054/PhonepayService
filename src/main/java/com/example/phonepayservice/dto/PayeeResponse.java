package com.example.phonepayservice.dto;

import com.example.phonepayservice.entity.SavedPayee;

public record PayeeResponse(long payeePhno, String nickname) {

    public static PayeeResponse from(SavedPayee p) {
        return new PayeeResponse(p.getPayeePhno(), p.getNickname());
    }
}
