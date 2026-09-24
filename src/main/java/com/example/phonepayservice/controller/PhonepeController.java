package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.AuthInterceptor;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.LoginRequest;
import com.example.phonepayservice.dto.LoginResponse;
import com.example.phonepayservice.dto.PageResponse;
import com.example.phonepayservice.dto.PayeeResponse;
import com.example.phonepayservice.dto.PaymentRequest;
import com.example.phonepayservice.dto.ProfileResponse;
import com.example.phonepayservice.dto.SavePayeeRequest;
import com.example.phonepayservice.dto.SendMoneyRequest;
import com.example.phonepayservice.dto.TransactionResponse;
import com.example.phonepayservice.service.PayeeService;
import com.example.phonepayservice.service.PhonepeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Every endpoint except /login needs "Authorization: Bearer <token>". The caller's phone number comes from that token
 * (see AuthInterceptor), never from a request parameter, so nobody can act as or look up somebody else.
 */
@RestController
@RequestMapping("/phonepe")
public class PhonepeController {
    private final PhonepeService phonepeService;
    private final PayeeService payeeService;

    public PhonepeController(PhonepeService phonepeService, PayeeService payeeService) {
        this.phonepeService = phonepeService;
        this.payeeService = payeeService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return phonepeService.login(request.phno(), request.pin());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        phonepeService.logout(AuthInterceptor.bearerToken(authorization));
    }

    @GetMapping("/profile")
    public ProfileResponse profile(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return phonepeService.profile(caller);
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return phonepeService.balance(caller);
    }

    @PostMapping("/sendmoney")
    public TransactionResponse sendMoney(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                         @Valid @RequestBody SendMoneyRequest request) {
        return TransactionResponse.from(phonepeService.sendMoney(caller, request.receiverPhno(), request.amount(), request.note(), request.idempotencyKey()), caller);
    }

    @PostMapping("/makepayment")
    public TransactionResponse makePayment(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                           @Valid @RequestBody PaymentRequest request) {
        return TransactionResponse.from(phonepeService.makePayment(caller, request.amount(), request.note(), request.idempotencyKey()), caller);
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> transactions(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "" + PhonepeService.DEFAULT_PAGE_SIZE) int size,
                          @RequestParam(required = false) Instant from,
                          @RequestParam(required = false) Instant to,
                          @RequestParam(required = false) Long counterparty,
                          @RequestParam(required = false) String noteContains) {
        return phonepeService.transactionsOf(caller, page, size, from, to, counterparty, noteContains).map(t -> TransactionResponse.from(t, caller));
    }

    @GetMapping("/transactions/{transactionId}")
    public TransactionResponse transaction(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                           @PathVariable long transactionId) {
        return TransactionResponse.from(phonepeService.transaction(caller, transactionId), caller);
    }

    // ---------- saved payees ----------

    @PostMapping("/payees")
    public PayeeResponse savePayee(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                   @Valid @RequestBody SavePayeeRequest request) {
        return payeeService.save(caller, request.payeePhno(), request.nickname());
    }

    @GetMapping("/payees")
    public List<PayeeResponse> payees(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return payeeService.listPayees(caller);
    }

    @DeleteMapping("/payees/{payeePhno}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePayee(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                            @PathVariable long payeePhno) {
        payeeService.delete(caller, payeePhno);
    }
}
