package com.example.phonepayservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

// One entry in someone's saved-contacts list: a phone number they pay often, with a label they chose for it.
// Never enforces that payeePhno is a real bank account - the same lazy-validation approach as sendMoney itself,
// which only finds out when the payment is actually attempted.
@Entity
@Table(name = "saved_payee",
        uniqueConstraints = @UniqueConstraint(name = "uk_saved_payee_owner_payee", columnNames = {"owner_phno", "payee_phno"}),
        indexes = @Index(name = "idx_saved_payee_owner_phno", columnList = "owner_phno"))
@Data
public class SavedPayee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "owner_phno")
    private long ownerPhno;
    @Column(name = "payee_phno")
    private long payeePhno;
    @Column(length = 50)
    private String nickname;
    private Instant createdAt;
}
