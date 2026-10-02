package com.vmarket.gateway.security;

import java.io.IOException;
import static com.vmarket.gateway.config.SearchContract.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/** Apply a streaming limit even without Content-Length; other uploads retain their policy. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SearchUploadLimitFilter extends OncePerRequestFilter {

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equalsIgnoreCase(request.getMethod()) || !IMAGE_PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		try {
			if (request.getContentLengthLong() > MAX_BODY) throw new UploadTooLarge();
			chain.doFilter(new HttpServletRequestWrapper(request) {
				private ServletInputStream limited;
				@Override public ServletInputStream getInputStream() throws IOException {
					if (limited == null) {
						ServletInputStream input = super.getInputStream();
						limited = new ServletInputStream() {
							private long count;
							private void check(int size) throws IOException {
								if (size > 0 && (count += size) > MAX_BODY) throw new UploadTooLarge();
							}
							@Override public int read() throws IOException { int value = input.read(); check(value < 0 ? 0 : 1); return value; }
							@Override public int read(byte[] b, int off, int len) throws IOException {
								int size = input.read(b, off, len); check(size); return size;
							}
							@Override public boolean isFinished() { return input.isFinished(); }
							@Override public boolean isReady() { return input.isReady(); }
							@Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
						};
					}
					return limited;
				}
			}, response);
		} catch (IOException | ServletException | RuntimeException ex) {
			Throwable cause = ex;
			while (cause != null && !(cause instanceof UploadTooLarge)) cause = cause.getCause();
			if (cause == null) throw ex;
			ErrorResponseWriter.write(response, 413, IMAGE_TOO_LARGE_CODE, IMAGE_TOO_LARGE_MESSAGE, IMAGE_PATH);
		}
	}

	private static class UploadTooLarge extends IOException { }
}
