package com.vmarket.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.vmarket.user.client.AuthServiceClient;

/** Dựng {@link AuthServiceClient} với base URL, khoá nội bộ và timeout từ {@link AuthServiceProperties}. */
@Configuration
public class AuthServiceClientConfig {

	@Bean
	AuthServiceClient authServiceClient(AuthServiceProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.getConnectTimeout());
		requestFactory.setReadTimeout(properties.getReadTimeout());

		RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
		return new AuthServiceClient(AuthServiceClient.buildRestClient(builder, properties));
	}
}
