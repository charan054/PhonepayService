package com.example.phonepayservice.configuration;

import com.example.phonepayservice.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    // Defaults for the two rate limits below; overridable via properties so a test suite that legitimately calls
    // these endpoints many times from one simulated address/account (PhonepeIntegrationTest) can raise its budget
    // instead of tripping over production-sized traffic assumptions.
    public static final int DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS = 10;
    public static final int DEFAULT_MAX_SENDMONEY_ATTEMPTS_PER_ACCOUNT = 20;
    static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final SessionService sessions;
    private final Clock clock;
    private final int maxLoginAttemptsPerAddress;
    private final int maxSendMoneyAttemptsPerAccount;

    public WebConfig(SessionService sessions,
                     Clock clock,
                     @Value("${phonepe.login.rate-limit.max-attempts:" + DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS + "}")
                     int maxLoginAttemptsPerAddress,
                     @Value("${phonepe.sendmoney.rate-limit.max-attempts:" + DEFAULT_MAX_SENDMONEY_ATTEMPTS_PER_ACCOUNT + "}")
                     int maxSendMoneyAttemptsPerAccount) {
        this.sessions = sessions;
        this.clock = clock;
        this.maxLoginAttemptsPerAddress = maxLoginAttemptsPerAddress;
        this.maxSendMoneyAttemptsPerAccount = maxSendMoneyAttemptsPerAccount;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Rate-limited per address: nothing else guards repeated login attempts before a session even exists.
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxLoginAttemptsPerAddress, RATE_WINDOW, clock),
                        HttpServletRequest::getRemoteAddr,
                        "Too many login attempts from this address. Please wait a minute and try again."))
                .addPathPatterns("/phonepe/login");

        registry.addInterceptor(new AuthInterceptor(sessions))
                .addPathPatterns("/phonepe/**")
                .excludePathPatterns("/phonepe/login");   // the only endpoint you can call without being logged in

        // Rate-limited per authenticated account (registered AFTER AuthInterceptor, so the phno attribute it sets
        // is already there): protects against a compromised or scripted client hammering transfers.
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxSendMoneyAttemptsPerAccount, RATE_WINDOW, clock),
                        request -> String.valueOf(request.getAttribute(AuthInterceptor.AUTHENTICATED_PHNO)),
                        "Too many transfer requests. Please wait a minute and try again."))
                .addPathPatterns("/phonepe/sendmoney");
    }
}
