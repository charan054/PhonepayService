package com.example.phonepayservice.dto;

import lombok.Data;

@Data
public class User {
    private int id;
    private long acno;
    private String username;
    private long phno;
    private double balance;

}
