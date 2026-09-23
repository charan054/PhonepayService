package com.example.phonepayservice.client;

import com.example.phonepayservice.dto.BankTransferRequest;
import com.example.phonepayservice.dto.BankUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

// Talk to the bank through BankGateway, which supplies the X-Service-Key header and turns the bank's answers
// and failures into meaningful exceptions. The Bank app now requires this header on every one of these calls.
@FeignClient(name = "Bankapplication", url = "${bank.service.url}")
public interface BankClient {
    // the bank's small summary of a user (name, account number, balance), not the full record with every transaction
    @GetMapping("/bank/displayuser")
    BankUser displayUser(@RequestHeader("X-Service-Key") String serviceKey, @RequestParam("phno") long phno);

    @PutMapping("/bank/withdrawByphno")
    String withdrawByphno(@RequestHeader("X-Service-Key") String serviceKey,
                          @RequestParam("phno") long phno, @RequestParam("balance") double balance);

    @PutMapping("/bank/depositByphno")
    String depositByphno(@RequestHeader("X-Service-Key") String serviceKey,
                         @RequestParam("phno") long phno, @RequestParam("balance") double balance);

    // Moves money between two accounts atomically; safe to resend with the same idempotencyKey.
    @PostMapping("/bank/transfer")
    String transfer(@RequestHeader("X-Service-Key") String serviceKey, @RequestBody BankTransferRequest request);
}
