package com.vmarket.cart.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.vmarket.events.EventPublisher;
import com.vmarket.events.EventType;
import com.vmarket.events.UserBehaviorTracked;

import lombok.extern.slf4j.Slf4j;

/**
 * Phát {@code UserBehaviorTracked(add_to_cart)} cho Recommendation (FR-REC-03).
 *
 * <p>Best-effort và bất đồng bộ: thêm giỏ là thao tác chính, tín hiệu gợi ý chỉ là phụ.
 * Gửi trên một luồng riêng với hàng đợi giới hạn nên broker chậm/sập không làm request
 * thêm giỏ phải chờ timeout kết nối; hàng đợi đầy thì bỏ sự kiện và ghi log.
 */
@Slf4j
@Component
public class BehaviorTracker implements DisposableBean {

	private static final int QUEUE_CAPACITY = 1000;

	private final EventPublisher publisher;
	private final ExecutorService executor;

	@Autowired
	public BehaviorTracker(ObjectProvider<EventPublisher> publisher) {
		this(publisher.getIfAvailable(), new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
					Thread thread = new Thread(runnable, "cart-behavior-events");
					thread.setDaemon(true);
					return thread;
				}));
	}

	BehaviorTracker(EventPublisher publisher, ExecutorService executor) {
		this.publisher = publisher;
		this.executor = executor;
	}

	/** Không phát gì — dùng cho test dựng {@link CartService} thủ công. */
	public static BehaviorTracker disabled() {
		return new BehaviorTracker(null, null);
	}

	public void addedToCart(String userId, String productId) {
		if (publisher == null || executor == null) return;
		UserBehaviorTracked event = new UserBehaviorTracked(userId, productId, UserBehaviorTracked.ADD_TO_CART);
		try {
			executor.execute(() -> {
				try {
					publisher.publish(EventType.USER_BEHAVIOR_TRACKED, event);
				} catch (RuntimeException ex) {
					log.warn("Không phát được UserBehaviorTracked add_to_cart: {}", ex.getClass().getSimpleName());
				}
			});
		} catch (RejectedExecutionException ex) {
			log.warn("Bỏ UserBehaviorTracked add_to_cart vì hàng đợi đầy");
		}
	}

	@Override
	public void destroy() {
		if (executor == null) return;
		executor.shutdown();
		try {
			executor.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
