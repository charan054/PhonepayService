package com.example.phonepayservice.configuration;

import com.example.phonepayservice.service.SessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final SessionService sessions;

    public WebConfig(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(sessions))
                .addPathPatterns("/phonepe/**")
                .excludePathPatterns("/phonepe/login");   // the only endpoint you can call without being logged in
    }
}
