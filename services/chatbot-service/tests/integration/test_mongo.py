"""ChatStore against a real MongoDB. CI starts one; locally: docker compose up -d mongo."""
import os
import unittest
import uuid
from datetime import datetime, timezone
from unittest.mock import patch

from fastapi.testclient import TestClient

from knowledge import HashingEmbedder, KnowledgeIndex
from main import create_app
from manage import faq_chunks, ingest_faq
from settings import Settings
from store import ChatStore
from support import bearer

BASE = "/api/ai/chat"


class MongoChecks(unittest.TestCase):
    def setUp(self):
        self.settings = Settings(jwt_secret="test-only-secret-0123456789-0123456789-0123456789",
                                 mongo_host=os.environ.get("MONGO_HOST", "localhost"),
                                 mongo_port=int(os.environ.get("MONGO_PORT", "27018")),
                                 db_name="vmarket_chatbot_test_" + uuid.uuid4().hex[:8])
        self.store = ChatStore(self.settings)
        self.store.ping()

    def tearDown(self):
        self.store.client.drop_database(self.settings.db_name)
        self.store.close()

    def test_knowledge_round_trip_and_reingest(self):
        embedder = HashingEmbedder()
        self.store.ensure_indexes()
        self.assertEqual(ingest_faq(self.store, embedder), (len(faq_chunks()), 0))
        self.assertEqual(ingest_faq(self.store, embedder), (0, 0))
        hits = KnowledgeIndex(self.store, embedder, self.settings).search("chính sách đổi trả hàng")
        self.assertIn("đổi trả", hits[0][0]["title"])
        self.assertEqual(self.store.chunks("another-embedder"), [])
        self.assertEqual(self.store.replace_source("faq", [], set()), len(faq_chunks()))

    def test_history_and_tickets_are_scoped_to_the_owner(self):
        answer = {"reply": "trả lời", "intent": "answer", "sources": []}
        conversation, _ = self.store.append_exchange("alice", "c" * 32, "câu hỏi một", answer, True)
        self.store.append_exchange("alice", conversation, "câu hỏi hai", answer, False)
        self.assertIsNone(self.store.conversation("bob", conversation))
        self.assertEqual(self.store.list_messages("bob", conversation), [])
        self.assertEqual(self.store.list_conversations("bob"), [])
        self.assertEqual(self.store.conversation("alice", conversation)["messageCount"], 4)
        self.assertEqual([(m["role"], m["content"]) for m in self.store.list_messages("alice", conversation)],
                         [("user", "câu hỏi một"), ("assistant", "trả lời"), ("user", "câu hỏi hai"), ("assistant", "trả lời")])
        self.assertEqual([m["content"] for m in self.store.recent_messages("alice", conversation, 2)], ["câu hỏi hai", "trả lời"])
        ticket = self.store.create_ticket("alice", conversation, "gặp nhân viên")
        self.assertEqual(self.store.list_tickets("bob"), [])
        self.assertEqual([t["_id"] for t in self.store.list_tickets()], [ticket["_id"]])

    def test_turns_saved_in_the_same_millisecond_do_not_interleave(self):
        self.store.ensure_indexes()
        moment = datetime(2026, 10, 9, 12, 0, 0, 123000, tzinfo=timezone.utc)
        with patch("store.now", return_value=moment):
            for number in range(4):
                self.store.append_exchange("alice", "d" * 32, f"hỏi {number}", {"reply": f"đáp {number}", "intent": "answer",
                                                                                 "sources": []}, number == 0)
        messages = self.store.list_messages("alice", "d" * 32)
        self.assertEqual([m["role"] for m in messages], ["user", "assistant"] * 4)
        for question, reply in zip(messages[::2], messages[1::2]):
            self.assertEqual((question["turnId"], reply["content"]), (reply["turnId"], question["content"].replace("hỏi", "đáp")))
        recent = self.store.recent_messages("alice", "d" * 32, 4)
        self.assertEqual(recent, messages[-4:])

    def test_api_end_to_end_on_mongodb(self):
        with TestClient(create_app(self.settings, ChatStore(self.settings))) as client:
            alice, bob = bearer("alice"), bearer("bob")
            self.assertEqual(client.get(BASE + "/health").json()["database"], "ready")
            first = client.post(BASE + "/messages", json={"message": "Chính sách đổi trả như thế nào?"}, headers=alice).json()
            self.assertEqual(first["intent"], "answer")
            path = f"{BASE}/conversations/{first['conversationId']}/messages"
            self.assertEqual([m["role"] for m in client.get(path, headers=alice).json()["messages"]], ["user", "assistant"])
            self.assertEqual(client.get(path, headers=bob).status_code, 404)
            self.assertEqual(client.get(BASE + "/conversations", headers=bob).json()["conversations"], [])


if __name__ == "__main__":
    unittest.main()
