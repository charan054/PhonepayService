package com.example.phonepayservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name="login")
public class LoginData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private long phno;
}
