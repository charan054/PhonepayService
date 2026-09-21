package com.example.phonepayservice.configuration;

import com.example.phonepayservice.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before every protected endpoint: finds the caller from their own token and hands that phone number to the
 * controller. Nothing is ever read from shared state, so one person can never act as another.
 */
public class AuthInterceptor implements HandlerInterceptor {
    public static final String AUTHENTICATED_PHNO = "authenticatedPhno";

    private final SessionService sessions;

    public AuthInterceptor(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;   // browser pre-flight requests carry no credentials
        }
        long phno = sessions.authenticate(bearerToken(request.getHeader("Authorization")));
        request.setAttribute(AUTHENTICATED_PHNO, phno);
        return true;
    }

    /** "Bearer abc" gives "abc"; anything else gives null. */
    public static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorizationHeader.substring(7).trim();
    }
}
