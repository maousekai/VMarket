package com.vmarket.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.order.service.OrderService;

/**
 * Xác nhận Spring MVC chọn đúng handler cho lỗi Optimistic Locking qua request HTTP (PBL6-53).
 * Service được mock vì các luồng thật đều khoá dòng nên không tạo ra xung đột version.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderOptimisticLockHttpTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	private static final String BUYER = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final String ORDER_ID = "01JC4X9Q2W5R8T6V8N0P4S6A2B";

	@Autowired MockMvc mockMvc;
	@MockitoBean OrderService orderService;

	@Test
	void optimisticLockFailure_returns409_notGeneric500() throws Exception {
		when(orderService.cancelMyOrder(any(), any()))
				.thenThrow(new ObjectOptimisticLockingFailureException("orders", ORDER_ID));

		mockMvc.perform(put("/api/orders/{id}/cancel", ORDER_ID)
				.header(HttpHeaders.AUTHORIZATION,
						TestTokens.bearer(TestTokens.accessToken(SECRET, BUYER, List.of("BUYER")))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
	}
}
