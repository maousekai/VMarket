package com.vmarket.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class PaymentConfig {
    @Bean
    Clock paymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient paymentHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }
}
