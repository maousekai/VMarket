package com.vmarket.user.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.util.StreamUtils;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Giữ lại body trong bộ nhớ để đọc được nhiều lần.
 *
 * <p>Body của request là luồng một chiều: đọc xong là hết. {@code IdempotencyFilter}
 * cần đọc để tính vân tay, nhưng controller cũng cần chính body đó — không có lớp
 * này thì controller nhận được body rỗng.
 *
 * <p>Hệ quả cần biết: body bị đọc hết ở tầng filter nên container không còn tự
 * phân tích tham số form ({@code application/x-www-form-urlencoded}) hay multipart
 * được nữa. user-service chỉ nhận JSON nên không ảnh hưởng; thêm endpoint dạng form
 * thì phải loại trừ nó ở {@code IdempotencyFilter}.
 */
class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
		super(request);
		this.body = StreamUtils.copyToByteArray(request.getInputStream());
	}

	byte[] getBody() {
		return body;
	}

	@Override
	public ServletInputStream getInputStream() {
		return new CachedBodyServletInputStream(body);
	}

	@Override
	public BufferedReader getReader() {
		String encoding = getCharacterEncoding();
		Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
		return new BufferedReader(new InputStreamReader(getInputStream(), charset));
	}

	private static final class CachedBodyServletInputStream extends ServletInputStream {

		private final ByteArrayInputStream buffer;

		private CachedBodyServletInputStream(byte[] body) {
			this.buffer = new ByteArrayInputStream(body);
		}

		@Override
		public int read() {
			return buffer.read();
		}

		@Override
		public int read(byte[] b, int off, int len) {
			return buffer.read(b, off, len);
		}

		@Override
		public boolean isFinished() {
			return buffer.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener listener) {
			// Chỉ có ý nghĩa với I/O bất đồng bộ; body đã nằm sẵn trong bộ nhớ nên
			// không bao giờ phải chờ dữ liệu.
			throw new UnsupportedOperationException("Body đã nằm trong bộ nhớ, không đọc bất đồng bộ");
		}
	}
}
