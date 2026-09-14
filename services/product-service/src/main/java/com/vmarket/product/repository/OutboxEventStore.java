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
}
