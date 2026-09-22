package com.vmarket.product.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.ReturnRestock;

public interface ReturnRestockRepository extends MongoRepository<ReturnRestock, String> {
	boolean existsByReturnId(String returnId);
	List<ReturnRestock> findAllByOrderId(String orderId);
}
