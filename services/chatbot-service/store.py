"""MongoDB persistence: Conversation, Message, KnowledgeChunk and SupportTicket.

Every read of a conversation, message or ticket is filtered by its owner's userId here, so a caller
cannot reach another user's history by guessing an id.
"""
import uuid
from datetime import timedelta

from pymongo import ASCENDING, DESCENDING, MongoClient, ReplaceOne

from schemas import now

MAX_HISTORY = 200


class ChatStore:
    def __init__(self, settings, client=None):
        self.client = client or MongoClient(settings.mongo_uri, serverSelectionTimeoutMS=int(settings.mongo_timeout * 1000),
                                            socketTimeoutMS=int(settings.mongo_timeout * 1000), tz_aware=False)
        db = self.client[settings.db_name]
        self.conversations, self.messages = db["conversations"], db["messages"]
        self.knowledge, self.tickets = db["knowledge_chunks"], db["support_tickets"]

    def ensure_indexes(self):
        self.conversations.create_index([("userId", ASCENDING), ("updatedAt", DESCENDING)])
        self.messages.create_index([("conversationId", ASCENDING), ("createdAt", ASCENDING)])
        self.knowledge.create_index([("source", ASCENDING)])
        self.tickets.create_index([("userId", ASCENDING), ("createdAt", DESCENDING)])

    def ping(self):
        self.client.admin.command("ping")

    def close(self):
        self.client.close()

    # ---- KnowledgeChunk ----
    def chunks(self, fingerprint):
        return list(self.knowledge.find({"embedder": fingerprint}))

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

    def list_messages(self, user_id, conversation_id, limit=MAX_HISTORY):
        found = self.messages.find({"conversationId": conversation_id, "userId": user_id})
        return list(found.sort("createdAt", ASCENDING).limit(limit))

    def recent_messages(self, user_id, conversation_id, limit):
        found = self.messages.find({"conversationId": conversation_id, "userId": user_id})
        return list(found.sort("createdAt", DESCENDING).limit(limit))[::-1]

    def append_exchange(self, user_id, conversation_id, question, answer, create):
        """Store one question/answer pair; `create` opens the conversation on its first message."""
        # MongoDB keeps milliseconds; the reply is stamped one later so history order is stable.
        asked = now()
        asked = asked.replace(microsecond=asked.microsecond // 1000 * 1000)
        if create:
            self.conversations.insert_one({"_id": conversation_id, "userId": user_id, "title": question[:80],
                                           "messageCount": 0, "createdAt": asked, "updatedAt": asked})
        reply = {"_id": uuid.uuid4().hex, "conversationId": conversation_id, "userId": user_id, "role": "assistant",
                 "content": answer["reply"], "intent": answer["intent"], "sources": answer["sources"],
                 "createdAt": asked + timedelta(milliseconds=1)}
        self.messages.insert_many([
            {"_id": uuid.uuid4().hex, "conversationId": conversation_id, "userId": user_id, "role": "user",
             "content": question, "createdAt": asked}, reply])
        self.conversations.update_one({"_id": conversation_id, "userId": user_id},
                                      {"$inc": {"messageCount": 2}, "$set": {"updatedAt": asked}})
        return conversation_id, reply["_id"]

    # ---- SupportTicket ----
    def create_ticket(self, user_id, conversation_id, question):
        ticket = {"_id": uuid.uuid4().hex[:12].upper(), "userId": user_id, "conversationId": conversation_id,
                  "question": question, "status": "OPEN", "createdAt": now()}
        self.tickets.insert_one(ticket)
        return ticket

    def list_tickets(self, user_id=None, limit=100):
        return list(self.tickets.find({} if user_id is None else {"userId": user_id})
                    .sort("createdAt", DESCENDING).limit(limit))
