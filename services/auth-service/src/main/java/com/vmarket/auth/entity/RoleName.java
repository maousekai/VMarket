package com.vmarket.auth.entity;

/**
 * 5 vai trò RBAC của hệ thống (SRS mục 7.2). Giá trị khớp cột {@code roles.name}
 * đã được seed sẵn trong migration {@code V1__init_auth_schema.sql}.
 */
public enum RoleName {
	GUEST,
	BUYER,
	SELLER,
	SHIPPER,
	ADMIN
}
