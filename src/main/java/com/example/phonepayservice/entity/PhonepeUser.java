package com.example.phonepayservice.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

@Entity
@Table(name="phonepeuser")
@Data
@JsonPropertyOrder({
        "id",
        "phno",
        "transactionid",
        "date"
})
public class PhonepeUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private long phno;
    private long transactionid;
    private Date date;
}
