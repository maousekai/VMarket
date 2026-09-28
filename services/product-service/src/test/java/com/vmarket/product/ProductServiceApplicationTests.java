package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.vmarket.events.ProductDeleted;
import com.vmarket.product.model.OutboxEvent;

@SpringBootTest
class ProductServiceApplicationTests {
	@Autowired MongoTemplate mongoTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void outboxPayloadRoundTripsThroughMongoConverter() {
		OutboxEvent source = new OutboxEvent("ProductDeleted", new ProductDeleted("product-1", "shop-1"));
		Document document = new Document();
		mongoTemplate.getConverter().write(source, document);

		OutboxEvent restored = mongoTemplate.getConverter().read(OutboxEvent.class, document);

		assertThat(restored.getId()).isEqualTo(source.getId());
		assertThat(restored.getPayload()).isEqualTo(new ProductDeleted("product-1", "shop-1"));
	}

}
