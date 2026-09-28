package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.AuthInterceptor;
import com.example.phonepayservice.dto.CreateUpiCollectRequest;
import com.example.phonepayservice.dto.UpiCollectRequestResponse;
import com.example.phonepayservice.service.UpiCollectRequestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * /phonepe/upi/collect/** is merchant-only (X-Service-Key, see ServiceKeyInterceptor) - a trusted backend like
 * OrderService asking to collect a payment from one of its own customers by their UPI ID. Everything under
 * /phonepe/upi/requests/** is the buyer's own side of the same request, gated the normal way (Bearer session,
 * see AuthInterceptor) like every other /phonepe/** endpoint.
 */
@RestController
@RequestMapping("/phonepe/upi")
public class UpiCollectController {
    private final UpiCollectRequestService upiCollectRequestService;

    public UpiCollectController(UpiCollectRequestService upiCollectRequestService) {
        this.upiCollectRequestService = upiCollectRequestService;
    }

    @PostMapping("/collect")
    public UpiCollectRequestResponse create(@Valid @RequestBody CreateUpiCollectRequest request) {
        return UpiCollectRequestResponse.from(upiCollectRequestService.create(
                request.merchantReference(), request.upiId(), request.amount(), request.note()));
    }

    @GetMapping("/collect/{merchantReference}")
    public UpiCollectRequestResponse getByMerchantReference(@PathVariable String merchantReference) {
        return UpiCollectRequestResponse.from(upiCollectRequestService.getByMerchantReference(merchantReference));
    }

    @GetMapping("/requests")
    public List<UpiCollectRequestResponse> myRequests(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return upiCollectRequestService.listPendingFor(caller).stream().map(UpiCollectRequestResponse::from).toList();
    }

    @PostMapping("/requests/{id}/approve")
    public UpiCollectRequestResponse approve(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                              @PathVariable long id) {
        return UpiCollectRequestResponse.from(upiCollectRequestService.approve(caller, id));
    }

    @PostMapping("/requests/{id}/decline")
    public UpiCollectRequestResponse decline(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                              @PathVariable long id) {
        return UpiCollectRequestResponse.from(upiCollectRequestService.decline(caller, id));
    }
}
