package com.example.phonepayservice.controller;

import com.example.phonepayservice.configuration.AuthInterceptor;
import com.example.phonepayservice.dto.InsightsResponse;
import com.example.phonepayservice.service.InsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own spending breakdown, from their Bearer session like every other /phonepe endpoint. */
@RestController
public class InsightsController {
    private final InsightsService insights;

    public InsightsController(InsightsService insights) {
        this.insights = insights;
    }

    // months: 1-12, default 6, ending with the current month
    @GetMapping("/phonepe/insights")
    public InsightsResponse insights(@RequestAttribute(AuthInterceptor.AUTHENTICATED_PHNO) long caller,
                                     @RequestParam(required = false) Integer months) {
        return insights.insights(caller, months);
    }
}
