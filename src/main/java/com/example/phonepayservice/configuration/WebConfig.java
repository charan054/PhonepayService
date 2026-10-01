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
    private final String serviceApiKey;

    public WebConfig(SessionService sessions,
                     Clock clock,
                     @Value("${phonepe.login.rate-limit.max-attempts:" + DEFAULT_MAX_LOGIN_ATTEMPTS_PER_ADDRESS + "}")
                     int maxLoginAttemptsPerAddress,
                     @Value("${phonepe.sendmoney.rate-limit.max-attempts:" + DEFAULT_MAX_SENDMONEY_ATTEMPTS_PER_ACCOUNT + "}")
                     int maxSendMoneyAttemptsPerAccount,
                     @Value("${internal.service.api-key}") String serviceApiKey) {
        this.sessions = sessions;
        this.clock = clock;
        this.maxLoginAttemptsPerAddress = maxLoginAttemptsPerAddress;
        this.maxSendMoneyAttemptsPerAccount = maxSendMoneyAttemptsPerAccount;
        this.serviceApiKey = serviceApiKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Rate-limited per address: nothing else guards repeated login attempts before a session even exists.
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxLoginAttemptsPerAddress, RATE_WINDOW, clock),
                        HttpServletRequest::getRemoteAddr,
                        "Too many login attempts from this address. Please wait a minute and try again."))
                .addPathPatterns("/phonepe/login");

        // Public, same reasoning as login: it's the bank's own public endpoint behind this proxy, and the bank
        // itself gates reset on the emailed code - this rate limit just stops one address from spamming requests.
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxLoginAttemptsPerAddress, RATE_WINDOW, clock),
                        HttpServletRequest::getRemoteAddr,
                        "Too many attempts from this address. Please wait a minute and try again."))
                .addPathPatterns("/phonepe/forgotpin/request");

        // Merchant-only (e.g. OrderService creating/checking a UPI collect request on a buyer's behalf) - gated
        // by the shared internal service key instead of a buyer's own Bearer session, since the merchant never
        // has one. Registered BEFORE AuthInterceptor's exclusion below is checked, so these paths never fall
        // through to requiring a Bearer token either. Both "/phonepe/upi/collect" (POST, no trailing segment)
        // AND "/phonepe/upi/collect/**" (GET .../{merchantReference}) are listed - "/**" alone does not match the
        // bare path with no trailing slash, which silently left the POST endpoint unguarded by this interceptor
        // and falling through to AuthInterceptor's Bearer-token check instead (a merchant call has no such token).
        registry.addInterceptor(new ServiceKeyInterceptor(serviceApiKey))
                .addPathPatterns("/phonepe/upi/collect", "/phonepe/upi/collect/**");

        registry.addInterceptor(new AuthInterceptor(sessions))
                .addPathPatterns("/phonepe/**")
                .excludePathPatterns("/phonepe/login", "/phonepe/upi/collect", "/phonepe/upi/collect/**",
                        "/phonepe/forgotpin/request", "/phonepe/forgotpin/reset");

        // Rate-limited per authenticated account (registered AFTER AuthInterceptor, so the phno attribute it sets
        // is already there): protects against a compromised or scripted client hammering either way of moving
        // money out of this account. Shares one combined budget, the same way Bankapplication's deposit/withdraw
        // share one budget - makePayment debits through the exact same bank call as sendMoney (and refund
        // through the deposit call), so leaving any of them unthrottled would have left the whole protection
        // with a hole in it.
        registry.addInterceptor(new RateLimitInterceptor(
                        new RateLimiter(maxSendMoneyAttemptsPerAccount, RATE_WINDOW, clock),
                        request -> String.valueOf(request.getAttribute(AuthInterceptor.AUTHENTICATED_PHNO)),
                        "Too many payment requests. Please wait a minute and try again."))
                .addPathPatterns("/phonepe/sendmoney", "/phonepe/makepayment", "/phonepe/transactions/*/refund");
    }
}
