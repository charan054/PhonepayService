package com.example.phonepayservice.configuration;

import com.example.phonepayservice.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @Test
    void allowedRequest_passesThrough() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.allow("1.2.3.4")).thenReturn(true);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, HttpServletRequest::getRemoteAddr, "Too many requests");

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void refusedRequest_throwsWithTheConfiguredMessage() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.allow("1.2.3.4")).thenReturn(false);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, HttpServletRequest::getRemoteAddr, "Too many requests");

        TooManyRequestsException e = assertThrows(TooManyRequestsException.class,
                () -> interceptor.preHandle(request, response, new Object()));
        assertEquals("Too many requests", e.getMessage());
    }

    @Test
    void anOptionsPreflight_isNeverRateLimited() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(request.getMethod()).thenReturn("OPTIONS");
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, HttpServletRequest::getRemoteAddr, "Too many requests");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verifyNoInteractions(limiter);
    }
}
