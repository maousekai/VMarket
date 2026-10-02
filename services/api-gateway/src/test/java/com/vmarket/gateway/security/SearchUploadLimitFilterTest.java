package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;

class SearchUploadLimitFilterTest {
	@Test void countsStreamWithoutContentLength() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/search/image") {
			@Override public long getContentLengthLong() { return -1; }
		};
		request.setContent(new byte[11_000_001]);
		MockHttpServletResponse response = new MockHttpServletResponse();
		new SearchUploadLimitFilter().doFilter(request, response, (req, res) -> {
			((HttpServletRequest) req).getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
		});
		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentAsString()).contains("IMAGE_TOO_LARGE");
	}
}
