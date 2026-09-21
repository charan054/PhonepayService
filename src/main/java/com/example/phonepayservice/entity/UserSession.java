package com.example.phonepayservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One login. Only a SHA-256 hash of the token is stored, so a copy of the table cannot be used to act as a user.
 */
@Data
@Entity
@Table(name="user_session")
public class UserSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    private long phno;
    private Instant createdAt;
    private Instant expiresAt;
}
