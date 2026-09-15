package com.vmarket.product.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.vmarket.product.model.InventoryReservation;

public interface InventoryReservationRepository extends MongoRepository<InventoryReservation, String> {
	Optional<InventoryReservation> findByOrderId(String orderId);
}
