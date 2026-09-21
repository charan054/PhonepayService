package com.example.phonepayservice.dto;

import java.time.Instant;

/** Send the token on every later request as: Authorization: Bearer &lt;token&gt; */
public record LoginResponse(String token, Instant expiresAt, long phno, String name) {
}
