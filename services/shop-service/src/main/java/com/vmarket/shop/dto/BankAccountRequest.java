package com.vmarket.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Tài khoản ngân hàng nhận tiền của gian hàng ({@code PUT /api/shops/me/bank-account}).
 *
 * <p>Tách khỏi {@link ShopRequest} vì người bán đổi thông tin này ở mục riêng
 * "Thông tin thanh toán", không phải gửi lại toàn bộ hồ sơ.
 */
@Schema(name = "BankAccountRequest", description = "Tài khoản ngân hàng nhận tiền")
public record BankAccountRequest(

		@Schema(example = "VCB", description = "Mã ngân hàng theo danh sách VietQR")
		@NotBlank(message = "Vui lòng chọn ngân hàng")
		@Pattern(regexp = BANK_CODE, message = "Mã ngân hàng không hợp lệ")
		String bankCode,

		@Schema(example = "0123456789")
		@NotBlank(message = "Vui lòng nhập số tài khoản")
		@Pattern(regexp = ACCOUNT_NUMBER, message = "Số tài khoản gồm 6 đến 19 chữ số")
		String bankAccountNumber) {

	static final String BANK_CODE = "^\\s*[A-Za-z0-9]{2,20}\\s*$";
	static final String ACCOUNT_NUMBER = "^\\s*\\d{6,19}\\s*$";
	static final String BANK_CODE_OR_BLANK = "^(\\s*|\\s*[A-Za-z0-9]{2,20}\\s*)$";
	static final String ACCOUNT_NUMBER_OR_BLANK = "^(\\s*|\\s*\\d{6,19}\\s*)$";
}
