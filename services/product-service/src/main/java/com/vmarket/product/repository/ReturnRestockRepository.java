package com.vmarket.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.ReturnRestock;

public interface ReturnRestockRepository extends MongoRepository<ReturnRestock, String> {
	boolean existsByReturnId(String returnId);
	Optional<ReturnRestock> findByReturnId(String returnId);
	List<ReturnRestock> findAllByOrderIdAndPendingTrue(String orderId);
	List<ReturnRestock> findAllByOrderId(String orderId);
}
