package com.example.phonepayservice.client;

import com.example.phonepayservice.dto.BankUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

// Talk to the bank through BankGateway, which turns the bank's answers and failures into meaningful exceptions.
@FeignClient(name = "Bankapplication", url = "${bank.service.url}")
public interface BankClient {
    // the bank's small summary of a user (name, account number, balance), not the full record with every transaction
    @GetMapping("/bank/displayuser")
    BankUser displayUser(@RequestParam("phno") long phno);

    @PutMapping("/bank/withdrawByphno")
    String withdrawByphno(@RequestParam("phno") long phno, @RequestParam("balance") double balance);

    @PutMapping("/bank/depositByphno")
    String depositByphno(@RequestParam("phno") long phno, @RequestParam("balance") double balance);
}
