package com.vmarket.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.vmarket.cart.CartLimits;
import com.vmarket.cart.config.CartProperties;
import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.exception.ApiException;
import com.vmarket.cart.exception.CartStorageException;
import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.UserBehaviorTracked;

import tools.jackson.databind.json.JsonMapper;

/** PBL6-37: thêm giỏ thành công thì phát add_to_cart; lỗi broker không ảnh hưởng giỏ. */
class BehaviorTrackerTest {

	/** Chạy ngay trên luồng gọi để test xác định; có thể giả lập hàng đợi đầy. */
	static class DirectExecutor extends AbstractExecutorService {
		boolean full;

		@Override
		public void execute(Runnable command) {
			if (full) throw new RejectedExecutionException("full");
			command.run();
		}

		@Override public void shutdown() { }
		@Override public List<Runnable> shutdownNow() { return List.of(); }
		@Override public boolean isShutdown() { return false; }
		@Override public boolean isTerminated() { return false; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
	}

	private final Map<String, String> store = new HashMap<>();
	private EventPublisher publisher;
	private DirectExecutor executor;
	private ValueOperations<String, String> ops;
	private CartService cartService;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
				.when(ops).set(anyString(), anyString(), any(Duration.class));
		when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
		publisher = mock(EventPublisher.class);
		executor = new DirectExecutor();
		cartService = new CartService(redis, JsonMapper.builder().build(), new CartProperties(),
				new BehaviorTracker(publisher, executor));
	}

	private static AddCartItemRequest add(String productId, int quantity) {
		return new AddCartItemRequest(productId, null, "shop-1", quantity, new BigDecimal("100000"));
	}

	@Test
	void successfulAddPublishesAddToCartForTheCartOwner() {
		cartService.addItem("buyer-1", add("p1", 2));

		verify(publisher).publish(EventType.USER_BEHAVIOR_TRACKED,
				new UserBehaviorTracked("buyer-1", "p1", UserBehaviorTracked.ADD_TO_CART));
	}

	@Test
	void rejectedAddDoesNotPublish() {
		cartService.addItem("buyer-1", add("p1", CartLimits.MAX_QUANTITY_PER_ITEM));
		verify(publisher).publish(any(), any());

		assertThatThrownBy(() -> cartService.addItem("buyer-1", add("p1", 1))).isInstanceOf(ApiException.class);
		verify(publisher).publish(any(), any());
	}

	@Test
	void redisFailureDoesNotPublish() {
		doThrow(new RedisConnectionFailureException("down")).when(ops).set(anyString(), anyString(), any(Duration.class));

		assertThatThrownBy(() -> cartService.addItem("buyer-1", add("p1", 1))).isInstanceOf(CartStorageException.class);
		verify(publisher, never()).publish(any(), any());
	}

	@Test
	void brokerFailureOrFullQueueNeverFailsTheCart() {
		doThrow(new IllegalStateException("broker down")).when(publisher).publish(any(), any());
		assertThat(cartService.addItem("buyer-1", add("p1", 1)).totalQuantity()).isEqualTo(1);

		executor.full = true;
		assertThat(cartService.addItem("buyer-1", add("p2", 1)).totalQuantity()).isEqualTo(2);
	}

	@Test
	void disabledTrackerIsANoOp() {
		BehaviorTracker.disabled().addedToCart("buyer-1", "p1");
		BehaviorTracker.disabled().destroy();
	}
}
