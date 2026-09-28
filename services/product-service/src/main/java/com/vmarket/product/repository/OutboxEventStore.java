package com.vmarket.product.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import com.vmarket.product.model.OutboxEvent;

/** Atomic claim/complete operations so multiple dispatcher instances do not publish concurrently. */
@Repository
public class OutboxEventStore {
	private final MongoTemplate mongoTemplate;

	public OutboxEventStore(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	public Optional<OutboxEvent> claimNext(String workerId, Instant now, Instant claimedUntil) {
		Criteria claimable = new Criteria().orOperator(
				Criteria.where("claimedUntil").exists(false),
				Criteria.where("claimedUntil").is(null),
				Criteria.where("claimedUntil").lt(now));
		Criteria pending = new Criteria().andOperator(
				Criteria.where("publishedAt").is(null),
				Criteria.where("deadAt").is(null),
				Criteria.where("nextAttemptAt").lte(now), claimable);
		Query query = new Query(pending).with(Sort.by(Sort.Direction.ASC, "createdAt"));
		Update update = new Update().set("claimedBy", workerId).set("claimedUntil", claimedUntil);
		return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
				FindAndModifyOptions.options().returnNew(true), OutboxEvent.class));
	}

	public boolean markPublished(String eventId, String workerId, Instant publishedAt) {
		Query query = Query.query(Criteria.where("_id").is(eventId).and("claimedBy").is(workerId)
				.and("publishedAt").is(null));
		Update update = new Update().set("publishedAt", publishedAt).set("lastError", null)
				.unset("claimedBy").unset("claimedUntil");
		return mongoTemplate.updateFirst(query, update, OutboxEvent.class).getModifiedCount() == 1;
	}

	public boolean markFailed(String eventId, String workerId, Instant nextAttemptAt, String error) {
		Query query = Query.query(Criteria.where("_id").is(eventId).and("claimedBy").is(workerId)
				.and("publishedAt").is(null));
		Update update = new Update().inc("attempts", 1).set("nextAttemptAt", nextAttemptAt)
				.set("lastError", error).unset("claimedBy").unset("claimedUntil");
		return mongoTemplate.updateFirst(query, update, OutboxEvent.class).getModifiedCount() == 1;
	}

	/**
	 * Đánh dấu event đã cạn lượt thử (vượt {@code app.outbox.max-attempts}) — event
	 * được GIỮ LẠI trong collection để xử lý thủ công (re-drive), không bị xoá.
	 * Re-drive: xoá {@code deadAt}/{@code deadReason} rồi đặt lại
	 * {@code nextAttemptAt} về hiện tại (xem docs/event-bus.md §7).
	 */
	public boolean markDead(String eventId, String workerId, String reason) {
		Query query = Query.query(Criteria.where("_id").is(eventId).and("claimedBy").is(workerId)
				.and("publishedAt").is(null));
		Update update = new Update().set("deadAt", Instant.now()).set("deadReason", reason)
				.set("lastError", reason).unset("claimedBy").unset("claimedUntil");
		return mongoTemplate.updateFirst(query, update, OutboxEvent.class).getModifiedCount() == 1;
	}

	/** Số event chờ được publish (đã tới lượt thử, chưa publish, chưa DEAD). */
	public long countPending(Instant now) {
		Query query = Query.query(new Criteria().andOperator(
				Criteria.where("publishedAt").is(null),
				Criteria.where("deadAt").is(null),
				Criteria.where("nextAttemptAt").lte(now)));
		return mongoTemplate.count(query, OutboxEvent.class);
	}

	/** Số event đã bị đánh dấu DEAD — chỉ số cần alert khi > 0. */
	public long countDead() {
		return mongoTemplate.count(Query.query(Criteria.where("deadAt").ne(null)), OutboxEvent.class);
	}
}
