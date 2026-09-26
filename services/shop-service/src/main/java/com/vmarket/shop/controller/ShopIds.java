package com.vmarket.shop.controller;

/**
 * Định dạng id gian hàng trên đường dẫn: ULID 26 ký tự. Kiểm tra ở controller để id
 * rác trả 400 ngay thay vì một câu truy vấn chỉ để trả 404.
 */
final class ShopIds {

	static final String PATTERN = "^[0-9A-Za-z]{26}$";

	static final String MESSAGE = "Mã gian hàng không hợp lệ";

	private ShopIds() {
	}
}
