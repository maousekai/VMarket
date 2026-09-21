package com.vmarket.user.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.user.entity.Address;

public interface AddressRepository extends JpaRepository<Address, String> {

	/** Địa chỉ mặc định lên đầu, sau đó mới nhất trước. */
	List<Address> findByUserIdOrderByIsDefaultDescCreatedAtDesc(String userId);

	/**
	 * Luôn tra cứu kèm {@code userId} thay vì {@code findById} thuần: người dùng A
	 * đoán được id địa chỉ của B cũng không đọc/sửa/xoá được (IDOR).
	 */
	Optional<Address> findByIdAndUserId(String id, String userId);

	Optional<Address> findByUserIdAndIsDefaultTrue(String userId);

	/**
	 * Địa chỉ mới nhất của user — dùng để chọn người kế nhiệm sau khi xoá địa chỉ
	 * mặc định.
	 *
	 * <p>Cố ý KHÔNG sắp theo {@code isDefault} như phương thức liệt kê ở trên: lúc
	 * gọi thì địa chỉ mặc định vừa bị xoá nên không dòng nào còn cờ, tiêu chí đó là
	 * vô nghĩa. {@code findFirst} để CSDL trả đúng một dòng thay vì nạp cả sổ địa chỉ
	 * chỉ để đọc phần tử đầu.
	 */
	Optional<Address> findFirstByUserIdOrderByCreatedAtDesc(String userId);

	long countByUserId(String userId);

	/**
	 * Bỏ cờ mặc định của mọi địa chỉ thuộc user, trừ địa chỉ {@code exceptId}.
	 *
	 * <p>Phải chạy TRƯỚC khi gán mặc định mới trong cùng transaction: partial unique
	 * index {@code uq_addresses_one_default_per_user} kiểm tra ngay ở từng câu lệnh,
	 * nên gán trước rồi mới xoá cờ cũ sẽ vi phạm ràng buộc.
	 *
	 * <p>{@code flushAutomatically} để các thay đổi còn trong persistence context
	 * được đẩy xuống DB trước; {@code clearAutomatically} để entity đang cache không
	 * giữ giá trị {@code isDefault} cũ đã lỗi thời sau câu update thẳng này.
	 *
	 * <p>{@code updatedAt} phải gán TAY: đây là câu UPDATE hàng loạt (JPQL thuần),
	 * nó đi thẳng xuống CSDL và không kích hoạt vòng đời entity của Hibernate, nên
	 * {@code @UpdateTimestamp} trên {@code Address.updatedAt} KHÔNG chạy. Bỏ qua thì
	 * dòng vừa bị gỡ cờ mặc định giữ nguyên {@code updated_at} cũ dù một trường người
	 * dùng nhìn thấy vừa đổi — sai cho audit và cho mọi thứ đồng bộ theo mốc sửa đổi.
	 * Truyền {@code Instant} từ ứng dụng thay vì {@code CURRENT_TIMESTAMP} để trùng
	 * nguồn thời gian với {@code @UpdateTimestamp} (đồng hồ JVM, không phải đồng hồ
	 * CSDL).
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Address a set a.isDefault = false, a.updatedAt = :updatedAt "
			+ "where a.userId = :userId and a.id <> :exceptId")
	int clearDefaultForUser(@Param("userId") String userId, @Param("exceptId") String exceptId,
			@Param("updatedAt") Instant updatedAt);
}
