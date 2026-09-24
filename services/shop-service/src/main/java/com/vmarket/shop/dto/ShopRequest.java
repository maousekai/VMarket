package com.vmarket.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Hồ sơ gian hàng — dùng chung cho đăng ký ({@code POST /api/shops}, FR-SHOP-01) và
 * cập nhật ({@code PUT /api/shops/me}, FR-SHOP-02): cả hai đều mô tả trạng thái đầy đủ
 * của hồ sơ, tách hai record giống hệt nhau chỉ tạo chỗ cho chúng lệch nhau về sau.
 *
 * <p><b>{@code PUT} là thay thế toàn bộ</b>: trường tuỳ chọn không gửi = {@code null} =
 * xoá (cùng ngữ nghĩa với {@code PUT /api/users/me} của user-service).
 *
 * <p>Không có {@code ownerId} hay {@code status}: chủ gian hàng lấy từ token, trạng thái
 * chỉ đổi qua các thao tác riêng (gửi lại hồ sơ, Admin duyệt...).
 *
 * <p>URL ảnh chỉ nhận {@code http(s)://}: giá trị này được đổ thẳng vào {@code <img src>}
 * ở frontend, nhận tuỳ ý thì {@code javascript:...} lọt qua được (NFR-SEC-05, XSS).
 */
@Schema(name = "ShopRequest", description = "Hồ sơ gian hàng")
public record ShopRequest(

		@Schema(example = "Tiệm Gốm Hội An")
		@NotBlank(message = "Tên gian hàng không được để trống")
		@Size(min = 3, max = 100, message = "Tên gian hàng từ 3 đến 100 ký tự")
		String name,

		@Schema(example = "Gốm thủ công làng Thanh Hà, giao toàn quốc.")
		@Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
		String description,

		@Schema(example = "https://cdn.vmarket.vn/shops/logo.png")
		@Size(max = 500, message = "URL logo tối đa 500 ký tự")
		@Pattern(regexp = ShopRequest.HTTP_URL, message = "URL logo phải bắt đầu bằng http:// hoặc https://")
		String logoUrl,

		@Schema(example = "https://cdn.vmarket.vn/shops/cover.jpg", description = "Ảnh bìa (FR-SHOP-02)")
		@Size(max = 500, message = "URL ảnh bìa tối đa 500 ký tự")
		@Pattern(regexp = ShopRequest.HTTP_URL, message = "URL ảnh bìa phải bắt đầu bằng http:// hoặc https://")
		String coverUrl,

		@Schema(example = "Đổi trả trong 7 ngày nếu sản phẩm lỗi do vận chuyển.",
				description = "Chính sách đổi trả / vận chuyển (FR-SHOP-02)")
		@Size(max = 5000, message = "Chính sách tối đa 5000 ký tự")
		String policies,

		@Schema(example = "lienhe@gomhoian.vn")
		@NotBlank(message = "Email liên hệ không được để trống")
		@Email(message = "Email liên hệ không đúng định dạng")
		@Size(max = 255, message = "Email liên hệ tối đa 255 ký tự")
		String contactEmail,

		@Schema(example = "0912345678")
		@NotBlank(message = "Số điện thoại liên hệ không được để trống")
		@Pattern(regexp = "^(0\\d{9}|\\+84\\d{9})$",
				message = "Số điện thoại phải là số di động Việt Nam hợp lệ")
		String contactPhone,

		@Schema(example = "Quảng Nam", description = "Địa chỉ kho / lấy hàng")
		@NotBlank(message = "Tỉnh/thành phố không được để trống")
		@Size(max = 100, message = "Tỉnh/thành phố tối đa 100 ký tự")
		String province,

		@Schema(example = "Hội An")
		@NotBlank(message = "Quận/huyện không được để trống")
		@Size(max = 100, message = "Quận/huyện tối đa 100 ký tự")
		String district,

		@Schema(example = "Thanh Hà")
		@NotBlank(message = "Phường/xã không được để trống")
		@Size(max = 100, message = "Phường/xã tối đa 100 ký tự")
		String ward,

		@Schema(example = "12 Phạm Phán")
		@NotBlank(message = "Địa chỉ chi tiết không được để trống")
		@Size(max = 255, message = "Địa chỉ chi tiết tối đa 255 ký tự")
		String streetAddress) {

	/**
	 * Bỏ trống ô nhập hoặc URL http(s) không chứa khoảng trắng.
	 *
	 * <p>Nhánh rỗng là {@code \s*} chứ không phải chuỗi rỗng: form gửi lên một ô chỉ có
	 * dấu cách vẫn là "bỏ trống" dưới mắt người dùng, nhưng nếu ở đây bắt lỗi thì họ
	 * nhận thông báo "URL phải bắt đầu bằng http://" cho một ô nhìn như trống rỗng.
	 * Service chuẩn hoá chuỗi trắng thành {@code null} nên CSDL không lưu khoảng trắng.
	 */
	static final String HTTP_URL = "^(\\s*|https?://\\S+)$";
}
