package com.vmarket.auth.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Danh sach admin dang hoat dong cho thong bao he thong (vd tu dong phan cong giao hang that bai).
 * Bao ve bang internal API key; khong di qua Gateway.
 */
@RestController
@RequestMapping("/internal/admins")
@RequiredArgsConstructor
public class InternalAdminDirectoryController {
	private final UserRepository users;

	@GetMapping
	public Map<String, Object> admins() {
		return Map.of("ids", users.findActiveIdsWithRole(RoleName.ADMIN));
	}
}
