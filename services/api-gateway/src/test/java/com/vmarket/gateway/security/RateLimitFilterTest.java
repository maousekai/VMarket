package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.vmarket.gateway.config.RateLimitProperties;

class RateLimitFilterTest {

	private RateLimitFilter filterWithProps(int capacity, boolean enabled) {
		RateLimitProperties props = new RateLimitProperties();
		props.setCapacity(capacity);
		props.setEnabled(enabled);
		props.setWindowSeconds(60);
		return new RateLimitFilter(props);
	}

	private MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, path);
		req.setRemoteAddr("10.0.0.1");
		return req;
	}

	private MockHttpServletRequest requestWithXff(String method, String path, String remoteAddr, String xff) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, path);
		req.setRemoteAddr(remoteAddr);
		if (xff != null) {
			req.addHeader("X-Forwarded-For", xff);
		}
		return req;
	}

	private MockHttpServletRequest preflightRequest() {
		MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/orders/1");
		req.setRemoteAddr("10.0.0.1");
		req.addHeader("Origin", "http://localhost:5173");
		req.addHeader("Access-Control-Request-Method", "GET");
		return req;
	}

	private MockHttpServletResponse doFilter(RateLimitFilter f, MockHttpServletRequest req) throws Exception {
		MockHttpServletResponse res = new MockHttpServletResponse();
		f.doFilter(req, res, new MockFilterChain());
		return res;
	}

	@Test
	void disabled_neverLimits() throws Exception {
		RateLimitFilter f = filterWithProps(1, false);

		for (int i = 0; i < 5; i++) {
			MockHttpServletResponse res = doFilter(f, request("GET", "/api/orders/1"));
			assertThat(res.getStatus()).isEqualTo(200);
		}
	}

	@Test
	void xffIsNotTrusted_ipIsRemoteAddr() throws Exception {
		// capacity = 2 cho cùng IP
		RateLimitFilter f = filterWithProps(2, true);

		// Request 1: same remoteAddr, XFF khác nhau
		MockHttpServletResponse res1 = doFilter(f, requestWithXff("GET", "/api/orders/1", "10.0.0.1", "1.1.1.1"));
		assertThat(res1.getStatus()).isEqualTo(200);

		// Request 2: same remoteAddr, XFF khác hẳn -> vẫn đếm vào cùng quota
		MockHttpServletResponse res2 = doFilter(f, requestWithXff("GET", "/api/orders/1", "10.0.0.1", "2.2.2.2"));
		assertThat(res2.getStatus()).isEqualTo(200);

		// Request 3: same remoteAddr, XFF khác nữa -> vượt quota (capacity = 2)
		MockHttpServletResponse res3 = doFilter(f, requestWithXff("GET", "/api/orders/1", "10.0.0.1", "3.3.3.3"));
		assertThat(res3.getStatus()).isEqualTo(429);
		assertThat(res3.getContentAsString()).contains("RATE_LIMITED");
	}

	@Test
	void preflight_isSkipped() throws Exception {
		RateLimitFilter f = filterWithProps(1, true);

		// Request 1: GET thu hết quota (capacity = 1)
		MockHttpServletResponse res1 = doFilter(f, request("GET", "/api/orders/1"));
		assertThat(res1.getStatus()).isEqualTo(200);

		// Request 2: preflight OPTIONS -> bỏ qua limiter
		MockHttpServletResponse res2 = doFilter(f, preflightRequest());
		assertThat(res2.getStatus()).isEqualTo(200);

		// Request 3: GET -> đã hết quota -> 429
		MockHttpServletResponse res3 = doFilter(f, request("GET", "/api/orders/2"));
		assertThat(res3.getStatus()).isEqualTo(429);
		assertThat(res3.getContentAsString()).contains("RATE_LIMITED");
	}

	@Test
	void differentRemoteAddr_independentCounters() throws Exception {
		RateLimitFilter f = filterWithProps(1, true);

		// Client A: remoteAddr = 10.0.0.1
		assertThat(doFilter(f, request("GET", "/api/orders/1")).getStatus()).isEqualTo(200);
		assertThat(doFilter(f, request("GET", "/api/orders/2")).getStatus()).isEqualTo(429);

		// Client B: remoteAddr = 10.0.0.2 -> counter độc lập
		MockHttpServletRequest reqB = request("GET", "/api/orders/3");
		reqB.setRemoteAddr("10.0.0.2");
		assertThat(doFilter(f, reqB).getStatus()).isEqualTo(200);
	}

	@Test
	void actuatorAndError_areExemptFromRateLimit() throws Exception {
		RateLimitFilter f = filterWithProps(1, true);

		// Thử gọi /actuator/** và /error liên tục vượt capacity (1), không bao giờ bị 429
		for (int i = 0; i < 5; i++) {
			assertThat(doFilter(f, request("GET", "/actuator/health")).getStatus()).isEqualTo(200);
			assertThat(doFilter(f, request("GET", "/actuator/info")).getStatus()).isEqualTo(200);
			assertThat(doFilter(f, request("GET", "/error")).getStatus()).isEqualTo(200);
		}
	}
}