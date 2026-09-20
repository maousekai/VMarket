package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.vmarket.user.dto.ErrorResponse;
import com.vmarket.user.exception.GlobalExceptionHandler;

/**
 * Ràng buộc CSDL bị vi phạm phải ra mã lỗi đúng, không phải 500.
 *
 * <p>Đây là chỗ khó kiểm bằng test API: partial unique index chỉ tồn tại trên
 * PostgreSQL (xem {@code V3__addresses_one_default_per_user.sql}), mà bộ test chạy
 * trên H2. Nên dựng thẳng exception như driver ném ra rồi kiểm phần dịch lỗi.
 */
class DataIntegrityMappingTest {

	private static final String SQLSTATE_UNIQUE = "23505";

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void indexMotDiaChiMacDinh_tra409_voiMaRieng() {
		var response = handler.handleDataIntegrity(
				violation("uq_addresses_one_default_per_user", SQLSTATE_UNIQUE));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(codeOf(response)).isEqualTo("DEFAULT_ADDRESS_CONFLICT");
	}

	@Test
	void tenRangBuocKemSchemaVaHauTo_vanNhanRa() {
		// H2 gắn schema và hậu tố vào tên index; PostgreSQL trả tên trần. So khớp
		// phải chịu được cả hai, nếu không trên H2 mọi xung đột đều rơi xuống 500.
		var response = handler.handleDataIntegrity(
				violation("PUBLIC.UQ_ADDRESSES_ONE_DEFAULT_PER_USER_INDEX_5", SQLSTATE_UNIQUE));

		assertThat(codeOf(response)).isEqualTo("DEFAULT_ADDRESS_CONFLICT");
	}

	@Test
	void viPhamUniqueKhac_tra409_maChung() {
		var response = handler.handleDataIntegrity(violation("uq_idempotency_user_key", SQLSTATE_UNIQUE));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(codeOf(response)).isEqualTo("DATA_CONFLICT");
	}

	@Test
	void viPhamKhongPhaiUnique_van500() {
		// NOT NULL bị vi phạm là lỗi của chính service. Trả 409 sẽ xui client thử lại
		// mãi cho một request không bao giờ thành công.
		var response = handler.handleDataIntegrity(violation("nn_addresses_phone", "23502"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(codeOf(response)).isEqualTo("INTERNAL_ERROR");
	}

	@Test
	void khongCoThongTinRangBuoc_van500() {
		var response = handler.handleDataIntegrity(new DataIntegrityViolationException("khong ro"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/** Dựng đúng hình dạng chuỗi nguyên nhân mà Spring tạo ra từ lỗi của Hibernate. */
	private static DataIntegrityViolationException violation(String constraintName, String sqlState) {
		var sqlException = new SQLException("vi pham rang buoc", sqlState);
		var hibernateException = new ConstraintViolationException("vi pham rang buoc", sqlException, constraintName);
		return new DataIntegrityViolationException("could not execute statement", hibernateException);
	}

	private static String codeOf(ResponseEntity<ErrorResponse> response) {
		return response.getBody().error().code();
	}
}
