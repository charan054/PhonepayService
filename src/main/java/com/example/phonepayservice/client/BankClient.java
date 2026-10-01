package com.example.phonepayservice.client;

import com.example.phonepayservice.dto.BankForgotPinRequest;
import com.example.phonepayservice.dto.BankLoginRequest;
import com.example.phonepayservice.dto.BankLoginResult;
import com.example.phonepayservice.dto.BankResetPinRequest;
import com.example.phonepayservice.dto.BankTransferRequest;
import com.example.phonepayservice.dto.BankUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

// Talk to the bank through BankGateway, which supplies the X-Service-Key header and turns the bank's answers
// and failures into meaningful exceptions. The Bank app now requires this header on every one of these calls,
// EXCEPT login: POST /bank/login is public on the bank's side, because that call itself is the credential check.
@FeignClient(name = "Bankapplication", url = "${bank.service.url}")
public interface BankClient {
    @PostMapping("/bank/login")
    BankLoginResult login(@RequestBody BankLoginRequest request);

    // Both public on the bank's side (no X-Service-Key), same reasoning as login: the request itself carries
    // whatever proof of identity it needs (a code emailed by the bank, for reset), not this service's own key.
    @PostMapping("/bank/forgotpin/request")
    void forgotPinRequest(@RequestBody BankForgotPinRequest request);

    @PostMapping("/bank/forgotpin/reset")
    void forgotPinReset(@RequestBody BankResetPinRequest request);

    // the bank's small summary of a user (name, account number, balance), not the full record with every transaction
    @GetMapping("/bank/displayuser")
    BankUser displayUser(@RequestHeader("X-Service-Key") String serviceKey, @RequestParam("phno") long phno);

    @PutMapping("/bank/withdrawByphno")
    String withdrawByphno(@RequestHeader("X-Service-Key") String serviceKey,
                          @RequestParam("phno") long phno, @RequestParam("balance") BigDecimal balance);

    @PutMapping("/bank/depositByphno")
    String depositByphno(@RequestHeader("X-Service-Key") String serviceKey,
                         @RequestParam("phno") long phno, @RequestParam("balance") BigDecimal balance);

    // Moves money between two accounts atomically; safe to resend with the same idempotencyKey.
    @PostMapping("/bank/transfer")
    String transfer(@RequestHeader("X-Service-Key") String serviceKey, @RequestBody BankTransferRequest request);
}
