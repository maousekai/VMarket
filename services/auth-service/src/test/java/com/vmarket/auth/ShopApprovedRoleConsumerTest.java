package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.event.ShopApprovedRoleConsumer;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.service.SellerRoleGrantService;
import com.vmarket.events.EventType;
import com.vmarket.events.ShopApproved;

@SpringBootTest
class ShopApprovedRoleConsumerTest {

	private static final String OWNER = "01JBQ9USER000000000SELLER1";

	@Autowired ShopApprovedRoleConsumer consumer;
	@Autowired SellerRoleGrantService grantService;
	@Autowired RoleRepository roles;
	@Autowired UserRepository users;
	@Autowired UserRoleRepository userRoles;

	private User owner;

	@BeforeEach
	void setUp() {
		userRoles.deleteAll();
		users.deleteAll();
		ensureRole(RoleName.BUYER);
		ensureRole(RoleName.SELLER);

		owner = new User();
		owner.setEmail("seller@example.com");
		owner.setUsername("seller");
		owner.setPasswordHash("$2a$10$not-a-real-hash");
		owner.setEmailVerified(true);
		users.saveAndFlush(owner);
		userRoles.saveAndFlush(new com.vmarket.auth.entity.UserRole(
				owner.getId(), roles.findByName(RoleName.BUYER).orElseThrow().getId()));
	}

	@AfterEach
	void cleanUp() {
		userRoles.deleteAll();
		users.deleteAll();
	}

	private Role ensureRole(RoleName name) {
		return roles.findByName(name).orElseGet(() -> {
			Role role = new Role();
			role.setName(name);
			return roles.saveAndFlush(role);
		});
	}

	@Test
	void declaresEventContract() {
		assertThat(consumer.eventType()).isEqualTo(EventType.SHOP_APPROVED);
		assertThat(consumer.payloadType()).isEqualTo(ShopApproved.class);
	}

	@Test
	void grantsSellerOnceAndIsIdempotent() {
		ShopApproved event = new ShopApproved("shop-1", owner.getId(), "Shop", "admin-1", false);

		consumer.handle(event, null);
		consumer.handle(event, null);

		String sellerId = roles.findByName(RoleName.SELLER).orElseThrow().getId();
		assertThat(userRoles.existsByUserIdAndRoleId(owner.getId(), sellerId)).isTrue();
		assertThat(userRoles.findByUserId(owner.getId())).hasSize(2); // BUYER + SELLER
	}

	@Test
	void unknownOwnerIsAcknowledgedWithoutError() {
		grantService.grant("01JBQ9USER000000000MISSING");

		assertThat(userRoles.count()).isEqualTo(1); // chỉ role BUYER của owner
	}

	@Test
	void missingSellerRoleFailsSoTheEventCanBeRetried() {
		userRoles.deleteAll();
		roles.deleteAll();

		assertThatThrownBy(() -> grantService.grant(owner.getId()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("SELLER");
	}
}
