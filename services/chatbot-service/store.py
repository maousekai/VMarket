"""MongoDB persistence: Conversation, Message, KnowledgeChunk and SupportTicket.

Every read of a conversation, message or ticket is filtered by its owner's userId here, so a caller
cannot reach another user's history by guessing an id.
"""
import itertools
import time
import uuid

from pymongo import ASCENDING, DESCENDING, MongoClient, ReplaceOne, ReturnDocument
from pymongo.errors import DuplicateKeyError

from schemas import now

# A turn's question and reply share createdAt and turnId, so two turns saved in the same millisecond
# still read back as question, reply, question, reply instead of interleaving.
MESSAGE_ORDER = [("createdAt", ASCENDING), ("turnId", ASCENDING), ("position", ASCENDING)]
SEQUENCE = itertools.count()


def turn_id():
    """32 hex chars that grow with each write of this process, so turns of the same millisecond keep
    the order they were saved in; the random tail keeps ids of different replicas apart."""
    return f"{time.time_ns():016x}{next(SEQUENCE) & 0xFFFF:04x}{uuid.uuid4().hex[:12]}"


MESSAGE_ORDER_INDEX = [("conversationId", ASCENDING), ("userId", ASCENDING)] + MESSAGE_ORDER


class ChatStore:
    def __init__(self, settings, client=None):
        self.client = client or MongoClient(settings.mongo_uri, serverSelectionTimeoutMS=int(settings.mongo_timeout * 1000),
                                            socketTimeoutMS=int(settings.mongo_timeout * 1000), tz_aware=False)
        db = self.client[settings.db_name]
        self.conversations, self.messages = db["conversations"], db["messages"]
        self.knowledge, self.tickets = db["knowledge_chunks"], db["support_tickets"]

    def ensure_indexes(self):
        self.conversations.create_index([("userId", ASCENDING), ("updatedAt", DESCENDING)])
        self.messages.create_index(MESSAGE_ORDER_INDEX)
        self.knowledge.create_index([("source", ASCENDING)])
        self.knowledge.create_index([("embedder", ASCENDING)])
        self.tickets.create_index([("userId", ASCENDING), ("createdAt", DESCENDING)])
        self.tickets.create_index([("createdAt", DESCENDING)])  # An ADMIN lists every ticket, newest first.
        self.tickets.create_index([("conversationId", ASCENDING)])
        self.mark_duplicate_tickets()
        # At most one OPEN ticket per conversation, so concurrent hand-off requests cannot open two.
        self.tickets.create_index([("userId", ASCENDING), ("conversationId", ASCENDING)], unique=True,
                                  partialFilterExpression={"status": "OPEN"}, name="one_open_ticket_per_conversation")

    def ping(self):
        self.client.admin.command("ping")

    def close(self):
        self.client.close()

    # ---- KnowledgeChunk ----
    def chunks(self, fingerprint):
        return list(self.knowledge.find({"embedder": fingerprint}))

    def has_chunks(self, fingerprint):
        return self.knowledge.find_one({"embedder": fingerprint}, {"_id": 1}) is not None

    def chunk_hashes(self, source, fingerprint):
        return {c["_id"]: c["contentHash"] for c in
                self.knowledge.find({"source": source, "embedder": fingerprint}, {"contentHash": 1})}

    def replace_source(self, source, changed, keep_ids):
        """Upsert changed chunks, then drop whatever the source no longer contains."""
        if changed:
            self.knowledge.bulk_write([ReplaceOne({"_id": c["_id"]}, c, upsert=True) for c in changed], ordered=False)
        return self.knowledge.delete_many({"source": source, "_id": {"$nin": list(keep_ids)}}).deleted_count

    # ---- Conversation / Message ----
    def conversation(self, user_id, conversation_id):
        return self.conversations.find_one({"_id": conversation_id, "userId": user_id})

    def list_conversations(self, user_id, limit=50):
        return list(self.conversations.find({"userId": user_id}).sort("updatedAt", DESCENDING).limit(limit))

    def message_page(self, user_id, conversation_id, limit, before=None):
        """The newest `limit` messages, oldest first, and whether older ones remain.

        `before` is the (createdAt, turnId, position) of the oldest message already shown; the page
        then holds the messages right before it in MESSAGE_ORDER.
        """
        query = {"conversationId": conversation_id, "userId": user_id}
        if before:
            created, turn, position = before
            query["$or"] = [{"createdAt": {"$lt": created}},
                            {"createdAt": created, "turnId": {"$lt": turn}},
                            {"createdAt": created, "turnId": turn, "position": {"$lt": position}}]
        found = list(self.messages.find(query).sort([(key, DESCENDING) for key, _ in MESSAGE_ORDER]).limit(limit + 1))
        return found[:limit][::-1], len(found) > limit

    def append_exchange(self, user_id, conversation_id, question, answer, create):
        """Store one question/answer pair; `create` opens the conversation on its first message."""
        asked, turn = now(), turn_id()
        base = {"conversationId": conversation_id, "userId": user_id, "turnId": turn, "createdAt": asked}
        if create:
            self.conversations.insert_one({"_id": conversation_id, "userId": user_id, "title": question[:80],
                                           "messageCount": 0, "createdAt": asked, "updatedAt": asked})
        reply = {**base, "_id": uuid.uuid4().hex, "position": 1, "role": "assistant", "content": answer["reply"],
                 "intent": answer["intent"], "sources": answer["sources"]}
        self.messages.insert_many([
            {**base, "_id": uuid.uuid4().hex, "position": 0, "role": "user", "content": question}, reply])
        self.conversations.update_one({"_id": conversation_id, "userId": user_id},
                                      {"$inc": {"messageCount": 2}, "$set": {"updatedAt": asked}})
        return conversation_id, reply["_id"]

    # ---- SupportTicket ----
    def open_ticket(self, user_id, conversation_id, question):
        """Return (ticket, created): the conversation's OPEN ticket, opened now if there was none.

        One atomic upsert guarded by the unique index, so concurrent requests all get the same ticket.
        """
        key = {"userId": user_id, "conversationId": conversation_id, "status": "OPEN"}
        fresh = {"_id": uuid.uuid4().hex[:12].upper(), "question": question, "createdAt": now()}
        try:
            existing = self.tickets.find_one_and_update(key, {"$setOnInsert": fresh}, upsert=True,
                                                        return_document=ReturnDocument.BEFORE)
        except DuplicateKeyError:
            # Another request inserted it between this one's match and insert: that ticket is the one.
            existing = self.tickets.find_one(key)
            if existing is None:
                raise
        return (existing, False) if existing else ({**key, **fresh}, True)

    def mark_duplicate_tickets(self):
        """Tickets opened twice before the unique index existed: keep the oldest OPEN one per conversation."""
        groups = self.tickets.aggregate([
            {"$match": {"status": "OPEN"}}, {"$sort": {"createdAt": ASCENDING}},
            {"$group": {"_id": {"userId": "$userId", "conversationId": "$conversationId"}, "ids": {"$push": "$_id"}}},
            {"$match": {"ids.1": {"$exists": True}}}])
        for group in groups:
            self.tickets.update_many({"_id": {"$in": group["ids"][1:]}},
                                     {"$set": {"status": "DUPLICATE", "duplicateOf": group["ids"][0]}})

    def ticket_owner(self, conversation_id):
        """The user who asked for support from this conversation, or None when nobody did."""
        ticket = self.tickets.find_one({"conversationId": conversation_id}, {"userId": 1})
        return ticket["userId"] if ticket else None

    def list_tickets(self, user_id=None, limit=100):
        return list(self.tickets.find({} if user_id is None else {"userId": user_id})
                    .sort("createdAt", DESCENDING).limit(limit))
