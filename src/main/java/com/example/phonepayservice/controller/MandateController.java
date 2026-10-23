package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.AuthInterceptor;
import com.example.phonepayservice.dto.MandateDtos;
import com.example.phonepayservice.dto.TransactionResponse;
import com.example.phonepayservice.entity.Transaction;
import com.example.phonepayservice.service.MandateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mandates. The customer's own routes (/phonepe/mandates) need their PhonePe session; the shop's routes
 * (/phonepe/merchant/mandates/...) need the shared service key, like the other merchant calls.
 */
@RestController
@RequestMapping("/phonepe")
public class MandateController {
    private final MandateService mandates;

    public MandateController(MandateService mandates) {
        this.mandates = mandates;
    }

    @PostMapping("/mandates")
    public MandateDtos.View create(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                   @Valid @RequestBody MandateDtos.CreateRequest request) {
        return mandates.create(caller, request);
    }

    @GetMapping("/mandates")
    public List<MandateDtos.View> mine(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return mandates.listFor(caller);
    }

    @DeleteMapping("/mandates/{id}")
    public MandateDtos.View revoke(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller, @PathVariable long id) {
        return mandates.revoke(caller, id);
    }

    @GetMapping("/merchant/mandates/{id}")
    public MandateDtos.View lookup(@PathVariable long id) {
        return mandates.lookup(id);
    }

    @PostMapping("/merchant/mandates/{id}/charge")
    public TransactionResponse charge(@PathVariable long id, @Valid @RequestBody MandateDtos.ChargeRequest request) {
        Transaction payment = mandates.charge(id, request);
        return TransactionResponse.from(payment, payment.getPhno());
    }
}
