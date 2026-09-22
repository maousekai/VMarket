package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.exception.GlobalExceptionHandler;
import com.vmarket.shop.repository.ShopRepository;
import com.vmarket.shop.repository.ShopStatusHistoryRepository;

/**
 * Nhánh "hai request song song cùng lọt qua bước kiểm tra trước": service kiểm tra
 * trùng chủ / trùng tên rồi mới ghi, nên giữa hai bước đó request thứ hai có thể chen
 * vào. Không dựng được race thật trong MockMvc, nên test ghi thẳng qua repository (bỏ
 * qua bước kiểm tra) để CSDL tự từ chối, rồi kiểm tra handler dịch đúng mã lỗi mà
 * client vẫn nhận khi bước kiểm tra trước bắt được.
 */
@SpringBootTest
class DataIntegrityMappingTest {

	@Autowired ShopRepository shopRepository;
	@Autowired ShopStatusHistoryRepository historyRepository;
	@Autowired GlobalExceptionHandler handler;

	@BeforeEach
	void clean() {
		historyRepository.deleteAll();
		shopRepository.deleteAll();
	}

	@Test
	void trungChuGianHang_oCSDL_duocDichThanh_SHOP_ALREADY_EXISTS() {
		shopRepository.saveAndFlush(shop("01JBQ9YDX7K3M8N5P2R4T6V8W0", "Tiệm Gốm Hội An"));

		var ex = catchThrowableOfType(DataIntegrityViolationException.class,
				() -> shopRepository.saveAndFlush(shop("01JBQ9YDX7K3M8N5P2R4T6V8W0", "Tên Khác")));

		var response = handler.handleDataIntegrity(ex);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().error().code()).isEqualTo("SHOP_ALREADY_EXISTS");
	}

	@Test
	void trungTen_oCSDL_duocDichThanh_SHOP_NAME_TAKEN() {
		shopRepository.saveAndFlush(shop("01JBQ9YDX7K3M8N5P2R4T6V8W0", "Tiệm Gốm Hội An"));

		var ex = catchThrowableOfType(DataIntegrityViolationException.class,
				() -> shopRepository.saveAndFlush(shop("01JBQ9YDX7K3M8N5P2R4T6V8W1", "TIỆM GỐM  HỘI AN")));

		var response = handler.handleDataIntegrity(ex);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().error().code()).isEqualTo("SHOP_NAME_TAKEN");
	}

	private static Shop shop(String ownerId, String name) {
		Shop shop = new Shop();
		shop.setOwnerId(ownerId);
		shop.setName(name);
		shop.setContactEmail("a@b.vn");
		shop.setContactPhone("0912345678");
		shop.setProvince("Quảng Nam");
		shop.setDistrict("Hội An");
		shop.setWard("Thanh Hà");
		shop.setStreetAddress("12 Phạm Phán");
		shop.setStatus(ShopStatus.PENDING);
		return shop;
	}
}
