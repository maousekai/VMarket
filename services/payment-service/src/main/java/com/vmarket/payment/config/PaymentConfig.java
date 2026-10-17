package com.vmarket.payment.config;
import java.time.Clock;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class PaymentConfig {
    @Bean Clock paymentClock() { return Clock.systemUTC(); }
    @Bean HttpClient paymentHttpClient() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(); }
}
