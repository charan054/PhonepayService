package com.example.phonepayservice.configuration;

import com.example.phonepayservice.exception.InvalidServiceKeyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the merchant-only UPI collect endpoints (creating/looking up a collect request by its merchant
 * reference) - these are called by a trusted backend like OrderService, never by a browser, so they're gated by
 * the shared internal service key rather than a buyer's own Bearer session token (which AuthInterceptor already
 * requires for every other /phonepe/** path, and which a merchant calling on a buyer's behalf never has).
 */
public class ServiceKeyInterceptor implements HandlerInterceptor {
    private final String serviceApiKey;

    public ServiceKeyInterceptor(String serviceApiKey) {
        this.serviceApiKey = serviceApiKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String provided = request.getHeader("X-Service-Key");
        if (provided != null && !provided.isEmpty()
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), serviceApiKey.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        throw new InvalidServiceKeyException("A valid X-Service-Key header is required for this operation.");
    }
}
