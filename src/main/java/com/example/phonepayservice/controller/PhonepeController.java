package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.AuthInterceptor;
import com.example.phonepayservice.dto.BalanceResponse;
import com.example.phonepayservice.dto.CreateMoneyRequestRequest;
import com.example.phonepayservice.dto.CreateRecurringPaymentRequest;
import com.example.phonepayservice.dto.LoginRequest;
import com.example.phonepayservice.dto.LoginResponse;
import com.example.phonepayservice.dto.MoneyRequestResponse;
import com.example.phonepayservice.dto.MonthlySummaryResponse;
import com.example.phonepayservice.dto.PageResponse;
import com.example.phonepayservice.dto.PayeeResponse;
import com.example.phonepayservice.dto.PaymentRequest;
import com.example.phonepayservice.dto.ProfileResponse;
import com.example.phonepayservice.dto.RecurringPaymentResponse;
import com.example.phonepayservice.dto.SavePayeeRequest;
import com.example.phonepayservice.dto.SendMoneyRequest;
import com.example.phonepayservice.dto.TransactionResponse;
import com.example.phonepayservice.service.MoneyRequestService;
import com.example.phonepayservice.service.PayeeService;
import com.example.phonepayservice.service.PhonepeService;
import com.example.phonepayservice.service.RecurringPaymentService;
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
    private final MoneyRequestService moneyRequestService;
    private final RecurringPaymentService recurringPaymentService;

    public PhonepeController(PhonepeService phonepeService, PayeeService payeeService,
                             MoneyRequestService moneyRequestService, RecurringPaymentService recurringPaymentService) {
        this.phonepeService = phonepeService;
        this.payeeService = payeeService;
        this.moneyRequestService = moneyRequestService;
        this.recurringPaymentService = recurringPaymentService;
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

    // month is "YYYY-MM"; omitted, it defaults to the current month.
    @GetMapping("/summary")
    public MonthlySummaryResponse summary(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                          @RequestParam(required = false) String month) {
        return phonepeService.monthlySummary(caller, month);
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

    // ---------- money requests ----------

    @PostMapping("/requests")
    public MoneyRequestResponse createRequest(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                              @Valid @RequestBody CreateMoneyRequestRequest request) {
        return moneyRequestService.create(caller, request.payerPhno(), request.amount(), request.note());
    }

    // Everything the caller is involved in, either as the one asking or the one being asked.
    @GetMapping("/requests")
    public List<MoneyRequestResponse> requests(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return moneyRequestService.listFor(caller);
    }

    @PostMapping("/requests/{requestId}/approve")
    public MoneyRequestResponse approveRequest(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                               @PathVariable long requestId) {
        return moneyRequestService.approve(caller, requestId);
    }

    @PostMapping("/requests/{requestId}/decline")
    public MoneyRequestResponse declineRequest(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                               @PathVariable long requestId) {
        return moneyRequestService.decline(caller, requestId);
    }

    // ---------- recurring payments ----------

    @PostMapping("/recurring")
    public RecurringPaymentResponse createRecurringPayment(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                                            @Valid @RequestBody CreateRecurringPaymentRequest request) {
        return recurringPaymentService.create(caller, request.payeePhno(), request.amount(), request.note(), request.intervalDays());
    }

    @GetMapping("/recurring")
    public List<RecurringPaymentResponse> recurringPayments(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return recurringPaymentService.listFor(caller);
    }

    @PostMapping("/recurring/{id}/pause")
    public RecurringPaymentResponse pauseRecurringPayment(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                                           @PathVariable long id) {
        return recurringPaymentService.pause(caller, id);
    }

    @PostMapping("/recurring/{id}/resume")
    public RecurringPaymentResponse resumeRecurringPayment(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                                            @PathVariable long id) {
        return recurringPaymentService.resume(caller, id);
    }

    // Soft-cancels (the row stays, marked CANCELLED) rather than deleting, so its history stays visible in the list.
    @DeleteMapping("/recurring/{id}")
    public RecurringPaymentResponse cancelRecurringPayment(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                                            @PathVariable long id) {
        return recurringPaymentService.cancel(caller, id);
    }
}
