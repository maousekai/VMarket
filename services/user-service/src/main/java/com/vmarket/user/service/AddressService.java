package com.vmarket.user.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.user.dto.AddressRequest;
import com.vmarket.user.dto.AddressResponse;
import com.vmarket.user.entity.Address;
import com.vmarket.user.exception.ApiException;
import com.vmarket.user.repository.AddressRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-USER-02 — Sổ địa chỉ: thêm, sửa, xoá và đặt địa chỉ mặc định.
 *
 * <p>Hai bất biến được giữ ở đây:
 * <ol>
 *   <li><b>Tối đa một địa chỉ mặc định</b> cho mỗi người dùng — còn được CSDL
 *       cưỡng chế bằng partial unique index.</li>
 *   <li><b>Còn địa chỉ thì luôn có đúng một cái mặc định</b> — địa chỉ đầu tiên tự
 *       thành mặc định, và xoá địa chỉ mặc định sẽ chỉ định người kế nhiệm.</li>
 * </ol>
 *
 * <p>Bất biến thứ hai quan trọng với luồng đặt hàng: nếu người dùng xoá địa chỉ
 * mặc định rồi hệ thống để họ không còn cái nào mặc định, trang thanh toán sẽ
 * không tự chọn được địa chỉ giao và đơn hàng đứng lại mà không rõ vì sao.
 *
 * <p>Mọi thao tác đều tra cứu theo cặp {@code (id, userId)} nên người dùng A không
 * chạm được vào địa chỉ của B dù có đoán đúng id (IDOR).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

	private final AddressRepository addressRepository;

	@Transactional(readOnly = true)
	public List<AddressResponse> list(String userId) {
		return addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
				.map(AddressResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public AddressResponse get(String userId, String addressId) {
		return AddressResponse.from(mustFind(userId, addressId));
	}

	@Transactional
	public AddressResponse create(String userId, AddressRequest request) {
		Address address = new Address();
		address.setUserId(userId);
		apply(address, request);

		// Địa chỉ đầu tiên tự thành mặc định: bắt người dùng thêm địa chỉ xong còn
		// phải bấm thêm một nút "đặt mặc định" là thừa, và quên bấm thì trang thanh
		// toán không có địa chỉ nào để chọn sẵn.
		address.setDefault(addressRepository.countByUserId(userId) == 0);

		Address saved = addressRepository.save(address);
		log.info("Thêm địa chỉ id={} userId={} (mặc định={})", saved.getId(), userId, saved.isDefault());
		return AddressResponse.from(saved);
	}

	@Transactional
	public AddressResponse update(String userId, String addressId, AddressRequest request) {
		Address address = mustFind(userId, addressId);
		// Cố ý không đụng tới cờ mặc định: đổi mặc định là hành động riêng, xem
		// javadoc của AddressRequest.
		apply(address, request);
		log.info("Cập nhật địa chỉ id={} userId={}", addressId, userId);
		return AddressResponse.from(addressRepository.save(address));
	}

	@Transactional
	public void delete(String userId, String addressId) {
		Address address = mustFind(userId, addressId);
		boolean wasDefault = address.isDefault();

		addressRepository.delete(address);
		// Đẩy lệnh xoá xuống CSDL trước khi chỉ định mặc định mới, nếu không partial
		// unique index vẫn thấy địa chỉ cũ đang mang cờ mặc định và chặn câu update.
		addressRepository.flush();

		if (wasDefault) {
			promoteNewDefault(userId);
		}
		log.info("Xoá địa chỉ id={} userId={}", addressId, userId);
	}

	/**
	 * Đặt một địa chỉ làm mặc định (FR-USER-02).
	 *
	 * <p>Thứ tự bắt buộc: <b>gỡ cờ của các địa chỉ khác trước</b>, gán cờ mới sau.
	 * Partial unique index kiểm tra ngay ở từng câu lệnh, nên làm ngược lại sẽ có
	 * khoảnh khắc hai dòng cùng mang cờ mặc định và câu lệnh bị từ chối.
	 */
	@Transactional
	public AddressResponse setDefault(String userId, String addressId) {
		Address address = mustFind(userId, addressId);
		if (address.isDefault()) {
			return AddressResponse.from(address);
		}

		addressRepository.clearDefaultForUser(userId, addressId);

		// clearDefaultForUser dùng clearAutomatically nên entity đang giữ đã bị gỡ
		// khỏi persistence context — phải nạp lại trước khi sửa, nếu không thay đổi
		// sẽ không được ghi xuống.
		Address reloaded = mustFind(userId, addressId);
		reloaded.setDefault(true);

		try {
			Address saved = addressRepository.saveAndFlush(reloaded);
			log.info("Đặt địa chỉ mặc định id={} userId={}", addressId, userId);
			return AddressResponse.from(saved);
		} catch (DataIntegrityViolationException ex) {
			// Hai request "đặt mặc định" chạy song song: partial unique index chặn
			// request tới sau. Trả 409 để client thử lại thay vì 500.
			log.warn("Xung đột khi đặt địa chỉ mặc định userId={} addressId={}", userId, addressId);
			throw ApiException.conflict("DEFAULT_ADDRESS_CONFLICT",
					"Một thao tác khác đang đổi địa chỉ mặc định, vui lòng thử lại");
		}
	}

	/** Sau khi xoá địa chỉ mặc định, chọn địa chỉ mới nhất còn lại làm mặc định. */
	private void promoteNewDefault(String userId) {
		List<Address> remaining = addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId);
		if (remaining.isEmpty()) {
			return;
		}
		Address next = remaining.get(0);
		next.setDefault(true);
		addressRepository.save(next);
		log.info("Địa chỉ id={} được đặt làm mặc định thay cho địa chỉ vừa xoá (userId={})",
				next.getId(), userId);
	}

	private Address mustFind(String userId, String addressId) {
		return addressRepository.findByIdAndUserId(addressId, userId)
				.orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ"));
	}

	private void apply(Address address, AddressRequest request) {
		address.setRecipientName(request.recipientName().trim());
		address.setPhone(request.phone().trim());
		address.setProvince(request.province().trim());
		address.setDistrict(request.district().trim());
		address.setWard(request.ward().trim());
		address.setStreetAddress(request.streetAddress().trim());
		address.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
	}
}
