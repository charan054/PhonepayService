package com.example.phonepayservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PhonepayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhonepayServiceApplication.class, args);
    }

}
