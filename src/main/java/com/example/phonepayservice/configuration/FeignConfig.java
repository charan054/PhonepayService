package com.example.phonepayservice.configuration;

import com.example.phonepayservice.client.BankClient;
import feign.Client;
import feign.hc5.ApacheHttp5Client;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Kept out of the main application class so slice tests (@WebMvcTest, @DataJpaTest) do not try to wire Feign.
@Configuration
@EnableFeignClients(basePackageClasses = BankClient.class)
public class FeignConfig {

    /**
     * The bank's deposit and withdraw ADD and SUBTRACT every time they are called, so sending one twice would move
     * the money twice. Feign's default client (java.net.HttpURLConnection) quietly re-sends a request once when the
     * connection drops, even if the bank had already processed it. This client never re-sends: a request goes out at
     * most once, and PhonepeService decides what to do about a failure. Timeouts still come from
     * spring.cloud.openfeign.client.config.default.* in application.properties.
     */
    @Bean
    public Client bankFeignClient() {
        CloseableHttpClient http = HttpClients.custom().disableAutomaticRetries().build();
        return new ApacheHttp5Client(http);
    }
}
