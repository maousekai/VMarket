package com.vmarket.auth.entity;

import com.github.f4b6a3.ulid.UlidCreator;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

/**
 * Khóa chính dùng chung cho các entity có một cột id: ULID (Crockford base32, 26
 * ký tự) sinh ở tầng ứng dụng theo quy ước repo (root CLAUDE.md — "ULIDs for
 * primary keys, sortable, unique across services").
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

	@Id
	@Column(length = 26, updatable = false, nullable = false)
	private String id;

	@PrePersist
	void assignId() {
		if (id == null || id.isBlank()) {
			id = UlidCreator.getUlid().toString();
		}
	}
}
