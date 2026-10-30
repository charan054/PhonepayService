package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.AuthInterceptor;
import com.example.phonepayservice.dto.DisputeDtos.DisputeResponse;
import com.example.phonepayservice.dto.DisputeDtos.RaiseRequest;
import com.example.phonepayservice.dto.DisputeDtos.ResolveRequest;
import com.example.phonepayservice.service.DisputeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * /phonepe/disputes needs the customer's Bearer session like every other endpoint; /phonepe/merchant/disputes is
 * staff-only (X-Service-Key, see ServiceKeyInterceptor), the same split the merchant refund endpoint uses.
 */
@RestController
public class DisputeController {
    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @PostMapping("/phonepe/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeResponse raise(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                 @Valid @RequestBody RaiseRequest request) {
        return disputes.raise(caller, request.transactionId(), request.reason());
    }

    @GetMapping("/phonepe/disputes")
    public List<DisputeResponse> mine(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller) {
        return disputes.mine(caller);
    }

    @GetMapping("/phonepe/merchant/disputes")
    public List<DisputeResponse> openQueue() {
        return disputes.openQueue();
    }

    @PostMapping("/phonepe/merchant/disputes/{id}/resolve")
    public DisputeResponse resolve(@PathVariable long id, @RequestBody ResolveRequest request) {
        return disputes.resolve(id, request.approve(), request.note());
    }
}
