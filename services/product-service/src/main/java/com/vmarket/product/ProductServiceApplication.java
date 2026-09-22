package com.vmarket.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import tools.jackson.databind.DeserializationFeature;

@SpringBootApplication
@EnableScheduling
public class ProductServiceApplication {
	@Bean
	JsonMapperBuilderCustomizer rejectFractionalIntegers() {
		return builder -> builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
	}

	public static void main(String[] args) {
		SpringApplication.run(ProductServiceApplication.class, args);
	}

}
