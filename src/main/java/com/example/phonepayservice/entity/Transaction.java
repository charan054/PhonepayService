package com.example.phonepayservice.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;
@JsonPropertyOrder({
        "id",
        "transactionId",
        "phno",
        "recieverno",
        "amount",
        "mode"
})
@Entity
@Table(name="transaction")
@Data
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private long transactionId;
    private long phno;
    private String mode;
    private double amount;
    private long recieverno;
}
