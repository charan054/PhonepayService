package com.example.phonepayservice.configuration;

import com.example.phonepayservice.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.function.Function;

/**
 * Refuses a request once its key (see keyExtractor) has used up its budget in the current window. Two endpoints
 * guarded by two different RateLimiter instances never share a budget.
 */
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RateLimiter limiter;
    private final Function<HttpServletRequest, String> keyExtractor;
    private final String message;

    public RateLimitInterceptor(RateLimiter limiter, Function<HttpServletRequest, String> keyExtractor, String message) {
        this.limiter = limiter;
        this.keyExtractor = keyExtractor;
        this.message = message;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;   // browser pre-flight requests carry no credentials
        }
        if (!limiter.allow(keyExtractor.apply(request))) {
            throw new TooManyRequestsException(message);
        }
        return true;
    }
}
