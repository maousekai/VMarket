package com.vmarket.shop.entity;

import com.github.f4b6a3.ulid.UlidCreator;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

/**
 * Khóa chính dùng chung: ULID (Crockford base32, 26 ký tự) sinh ở tầng ứng dụng.
 *
 * <p>Giống hệt {@code com.vmarket.user.entity.BaseEntity} / auth-service — cố ý chép
 * lại thay vì tách thư viện chung (xem lý do ở javadoc bên user-service).
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

	/** Độ dài ULID chuỗi (Crockford base32) — dùng chung cho mọi cột id / FK. */
	public static final int ID_LENGTH = 26;

	@Id
	@Column(length = ID_LENGTH, updatable = false, nullable = false)
	private String id;

	@PrePersist
	void assignId() {
		if (id == null || id.isBlank()) {
			id = UlidCreator.getUlid().toString();
		}
	}

	/**
	 * Bằng nhau theo {@code id}. Entity chưa persist ({@code id == null}) chỉ bằng
	 * chính nó. {@code hashCode} cố định theo lớp để không đổi trước/sau khi gán id.
	 */
	@Override
	public final boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof BaseEntity other) || getClass() != o.getClass()) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public final int hashCode() {
		return getClass().hashCode();
	}
}
