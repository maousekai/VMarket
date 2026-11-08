package com.vmarket.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.order.config.InternalApiProperties;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderItem;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.repository.OrderRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest(properties =
		"spring.datasource.url=jdbc:h2:mem:order_stats;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OrderStatsApiTest {

	private static final String SHOP = "01JBQ9SHOP0000000000TEST01";
	private static final String OTHER_SHOP = "01JBQ9SHOP0000000000TEST02";
	private static final Instant FROM = Instant.parse("2026-10-30T17:00:00Z"); // 31/10 00:00 VN
	private static final Instant TO = Instant.parse("2026-11-01T17:00:00Z");   // 02/11 00:00 VN

	@Autowired MockMvc mvc;
	@Autowired OrderRepository orders;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager em;
	@Autowired InternalApiProperties key;

	@AfterEach
	void cleanUp() {
		orders.deleteAll();
	}

	private Order order(OrderStatus status, Instant createdAt, ItemSpec... items) {
		Order order = new Order();
		order.setUserId("buyer-1");
		order.setStatus(status);
		order.setTotalAmount(BigDecimal.ZERO);
		order.setRecipientName("Người mua");
		order.setPhone("0901234567");
		order.setProvince("Đà Nẵng");
		order.setWard("Thạch Thang");
		order.setStreetAddress("54 Nguyễn Lương Bằng");
		BigDecimal total = BigDecimal.ZERO;
		for (ItemSpec spec : items) {
			OrderItem item = new OrderItem();
			item.setOrder(order);
			item.setProductId(spec.productId);
			item.setShopId(spec.shopId);
			item.setQuantity(spec.quantity);
			item.setUnitPrice(spec.lineTotal.divide(BigDecimal.valueOf(spec.quantity)));
			item.setLineTotal(spec.lineTotal);
			order.getItems().add(item);
			total = total.add(spec.lineTotal);
		}
		order.setTotalAmount(total);
		orders.saveAndFlush(order);
		jdbc.update("update orders set created_at = ? where id = ?", Timestamp.from(createdAt), order.getId());
		em.clear();
		return order;
	}

	private record ItemSpec(String productId, String shopId, int quantity, BigDecimal lineTotal) {
	}

	@Test
	void aggregatesOnlyThisShopInVietnamTimeAndZeroFillsDays() throws Exception {
		Order a = order(OrderStatus.PENDING, Instant.parse("2026-10-30T17:30:00Z"),
				new ItemSpec("p1", SHOP, 2, new BigDecimal("100.00")),
				new ItemSpec("p2", SHOP, 1, new BigDecimal("50.00")));
		order(OrderStatus.CANCELLED, Instant.parse("2026-10-31T05:00:00Z"),
				new ItemSpec("p1", SHOP, 9, new BigDecimal("900.00")));
		order(OrderStatus.DELIVERED, Instant.parse("2026-10-31T06:00:00Z"),
				new ItemSpec("px", OTHER_SHOP, 1, new BigDecimal("999.00")));
		Order d = order(OrderStatus.DELIVERED, Instant.parse("2026-10-31T18:00:00Z"),
				new ItemSpec("p3", SHOP, 1, new BigDecimal("200.00")));

		mvc.perform(get("/internal/orders/shops/{shopId}/stats", SHOP)
						.param("from", FROM.toString())
						.param("to", TO.toString())
						.header("X-Internal-Api-Key", key.getApiKey()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.grossRevenue").value(350.00))
				.andExpect(jsonPath("$.summary.deliveredRevenue").value(200.00))
				.andExpect(jsonPath("$.summary.totalOrders").value(3))
				.andExpect(jsonPath("$.summary.cancelledOrders").value(1))
				.andExpect(jsonPath("$.ordersByStatus[0].status").value("PENDING"))
				.andExpect(jsonPath("$.ordersByStatus[0].count").value(1))
				.andExpect(jsonPath("$.ordersByStatus[5].status").value("COMPLETED"))
				.andExpect(jsonPath("$.ordersByStatus[5].count").value(0))
				.andExpect(jsonPath("$.ordersByStatus[6].status").value("CANCELLED"))
				.andExpect(jsonPath("$.ordersByStatus[6].count").value(1))
				.andExpect(jsonPath("$.daily.length()").value(2))
				.andExpect(jsonPath("$.daily[0].date").value("2026-10-31"))
				.andExpect(jsonPath("$.daily[0].revenue").value(150.00))
				.andExpect(jsonPath("$.daily[0].orders").value(2))
				.andExpect(jsonPath("$.daily[1].date").value("2026-11-01"))
				.andExpect(jsonPath("$.daily[1].revenue").value(200.00))
				.andExpect(jsonPath("$.daily[1].orders").value(1))
				.andExpect(jsonPath("$.topProducts[0].productId").value("p1"))
				.andExpect(jsonPath("$.topProducts[0].quantity").value(2))
				.andExpect(jsonPath("$.topProducts[1].productId").value("p3"))
				.andExpect(jsonPath("$.topProducts[1].quantity").value(1))
				.andExpect(jsonPath("$.topProducts[2].productId").value("p2"));

		mvc.perform(get("/internal/orders/shops/{shopId}/recent", SHOP)
						.param("limit", "3")
						.header("X-Internal-Api-Key", key.getApiKey()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].orderId").value(d.getId()))
				.andExpect(jsonPath("$[0].shopSubtotal").value(200.00))
				.andExpect(jsonPath("$[0].itemCount").value(1))
				.andExpect(jsonPath("$[1].status").value("CANCELLED"))
				.andExpect(jsonPath("$[1].shopSubtotal").value(900.00))
				.andExpect(jsonPath("$[2].orderId").value(a.getId()))
				.andExpect(jsonPath("$[2].shopSubtotal").value(150.00))
				.andExpect(jsonPath("$[2].itemCount").value(3));
	}

	@Test
	void recentSubtotalCountsOnlyThisShopWhenOrderHasSeveralShops() throws Exception {
		Order mixed = order(OrderStatus.PROCESSING, Instant.parse("2026-10-31T05:00:00Z"),
				new ItemSpec("p1", SHOP, 2, new BigDecimal("100.00")),
				new ItemSpec("px", OTHER_SHOP, 7, new BigDecimal("700.00")));

		mvc.perform(get("/internal/orders/shops/{shopId}/recent", SHOP)
						.header("X-Internal-Api-Key", key.getApiKey()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].orderId").value(mixed.getId()))
				.andExpect(jsonPath("$[0].shopSubtotal").value(100.00))
				.andExpect(jsonPath("$[0].itemCount").value(2));
	}

	@Test
	void rejectsMissingKeyAndInvalidRangeOrLimit() throws Exception {
		mvc.perform(get("/internal/orders/shops/{shopId}/stats", SHOP)
						.param("from", FROM.toString())
						.param("to", TO.toString()))
				.andExpect(status().isUnauthorized());

		mvc.perform(get("/internal/orders/shops/{shopId}/stats", SHOP)
						.param("from", TO.toString())
						.param("to", FROM.toString())
						.header("X-Internal-Api-Key", key.getApiKey()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RANGE"));

		mvc.perform(get("/internal/orders/shops/{shopId}/recent", SHOP)
						.param("limit", "0")
						.header("X-Internal-Api-Key", key.getApiKey()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_LIMIT"));
	}
}
