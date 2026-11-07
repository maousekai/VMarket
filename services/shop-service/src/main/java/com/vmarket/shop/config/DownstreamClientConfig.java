package com.vmarket.shop.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.vmarket.shop.client.OrderServiceClient;
import com.vmarket.shop.client.ProductServiceClient;
import com.vmarket.shop.client.UserServiceClient;

/** Dựng RestClient cho các service downstream với timeout và khoá nội bộ dùng chung. */
@Configuration
public class DownstreamClientConfig {

	@Bean
	UserServiceClient userServiceClient(DownstreamProperties properties) {
		return new UserServiceClient(client(properties, properties.getUserServiceUrl()));
	}

	@Bean
	OrderServiceClient orderServiceClient(DownstreamProperties properties) {
		return new OrderServiceClient(client(properties, properties.getOrderServiceUrl()));
	}

	@Bean
	ProductServiceClient productServiceClient(DownstreamProperties properties) {
		return new ProductServiceClient(client(properties, properties.getProductServiceUrl()));
	}

	private static RestClient client(DownstreamProperties properties, String baseUrl) {
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(properties.getConnectTimeout())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.getReadTimeout());
		return RestClient.builder()
				.baseUrl(baseUrl)
				.defaultHeader(DownstreamProperties.INTERNAL_API_KEY_HEADER, properties.getInternalApiKey())
				.requestFactory(requestFactory)
				.build();
	}
}
