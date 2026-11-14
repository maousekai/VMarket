package com.vmarket.review.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.review.entity.Review;
import com.vmarket.review.entity.ReviewStatus;

/**
 * Truy vấn đánh giá. Các truy vấn danh sách nạp sẵn {@code reply} (to-one, an toàn khi phân trang);
 * ảnh nạp theo lô nhờ {@code @BatchSize} để tránh N+1 mà không phải fetch-join collection khi phân
 * trang.
 */
public interface ReviewRepository extends JpaRepository<Review, String> {

	boolean existsByOrderIdAndProductId(String orderId, String productId);

	@EntityGraph(attributePaths = "reply")
	@Query("""
			select r from Review r
			where r.productId = :productId and r.status = :status
			  and (:rating is null or r.rating = :rating)
			""")
	Page<Review> findByProduct(@Param("productId") String productId, @Param("status") ReviewStatus status,
			@Param("rating") Integer rating, Pageable pageable);

	@EntityGraph(attributePaths = "reply")
	@Query("""
			select r from Review r
			where r.shopId = :shopId
			  and (:status is null or r.status = :status)
			  and (:rating is null or r.rating = :rating)
			""")
	Page<Review> findByShop(@Param("shopId") String shopId, @Param("status") ReviewStatus status,
			@Param("rating") Integer rating, Pageable pageable);

	/**
	 * Danh sách cho seller: như {@link #findByShop} và thêm lọc đã/chưa phản hồi ({@code replied} null = tất cả).
	 */
	@EntityGraph(attributePaths = "reply")
	@Query("""
			select r from Review r
			where r.shopId = :shopId
			  and (:status is null or r.status = :status)
			  and (:rating is null or r.rating = :rating)
			  and (:replied is null
			       or (:replied = true and exists (select 1 from SellerReply sr where sr.review = r))
			       or (:replied = false and not exists (select 1 from SellerReply sr where sr.review = r)))
			""")
	Page<Review> findForSeller(@Param("shopId") String shopId, @Param("status") ReviewStatus status,
			@Param("rating") Integer rating, @Param("replied") Boolean replied, Pageable pageable);

	@EntityGraph(attributePaths = "reply")
	@Query("""
			select r from Review r
			where r.buyerId = :buyerId and (:orderId is null or r.orderId = :orderId)
			""")
	Page<Review> findByBuyer(@Param("buyerId") String buyerId, @Param("orderId") String orderId, Pageable pageable);

	@EntityGraph(attributePaths = "reply")
	@Query("""
			select r from Review r
			where (:status is null or r.status = :status)
			  and (:productId is null or r.productId = :productId)
			  and (:shopId is null or r.shopId = :shopId)
			""")
	Page<Review> search(@Param("status") ReviewStatus status, @Param("productId") String productId,
			@Param("shopId") String shopId, Pageable pageable);

	/** Phân bố số sao của sản phẩm — mỗi dòng {@code [rating, count]}, chỉ đánh giá đang hiển thị. */
	@Query("""
			select r.rating, count(r) from Review r
			where r.productId = :productId and r.status = com.vmarket.review.entity.ReviewStatus.VISIBLE
			group by r.rating
			""")
	List<Object[]> productDistribution(@Param("productId") String productId);

	/** Phân bố số sao của toàn bộ sản phẩm trong gian hàng. */
	@Query("""
			select r.rating, count(r) from Review r
			where r.shopId = :shopId and r.status = com.vmarket.review.entity.ReviewStatus.VISIBLE
			group by r.rating
			""")
	List<Object[]> shopDistribution(@Param("shopId") String shopId);
}
