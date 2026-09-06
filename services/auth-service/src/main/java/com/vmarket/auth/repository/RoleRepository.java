package com.vmarket.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;

public interface RoleRepository extends JpaRepository<Role, String> {

	Optional<Role> findByName(RoleName name);
}
