"""ChatStore against a real MongoDB. CI starts one; locally: docker compose up -d mongo."""
import os
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
import uuid
from datetime import datetime, timezone
from unittest.mock import patch

from fastapi.testclient import TestClient
from pymongo import DESCENDING

from knowledge import HashingEmbedder, KnowledgeIndex
from main import create_app
from manage import faq_chunks, ingest_faq
from settings import Settings
from schemas import cursor_of, parse_cursor
from store import MESSAGE_ORDER, ChatStore
from support import bearer

BASE = "/api/ai/chat"


def stages(plan):
    """Every stage name of an explain plan, whatever shape the server version nests it in."""
    children = plan.values() if isinstance(plan, dict) else plan if isinstance(plan, list) else ()
    found = {plan["stage"]} if isinstance(plan, dict) and "stage" in plan else set()
    return found.union(*map(stages, children))


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
        self.assertEqual(self.store.message_page("bob", conversation, 10), ([], False))
        self.assertEqual(self.store.list_conversations("bob"), [])
        self.assertEqual(self.store.conversation("alice", conversation)["messageCount"], 4)
        self.assertEqual([(m["role"], m["content"]) for m in self.store.message_page("alice", conversation, 10)[0]],
                         [("user", "câu hỏi một"), ("assistant", "trả lời"), ("user", "câu hỏi hai"), ("assistant", "trả lời")])
        self.assertEqual([m["content"] for m in self.store.message_page("alice", conversation, 2)[0]], ["câu hỏi hai", "trả lời"])
        ticket, _ = self.store.open_ticket("alice", conversation, "gặp nhân viên")
        self.assertEqual(self.store.list_tickets("bob"), [])
        self.assertEqual([t["_id"] for t in self.store.list_tickets()], [ticket["_id"]])

    def test_turns_saved_in_the_same_millisecond_do_not_interleave(self):
        self.store.ensure_indexes()
        moment = datetime(2026, 10, 9, 12, 0, 0, 123000, tzinfo=timezone.utc)
        with patch("store.now", return_value=moment):
            for number in range(4):
                self.store.append_exchange("alice", "d" * 32, f"hỏi {number}", {"reply": f"đáp {number}", "intent": "answer",
                                                                                 "sources": []}, number == 0)
        messages, more = self.store.message_page("alice", "d" * 32, 10)
        self.assertEqual(([m["role"] for m in messages], more), (["user", "assistant"] * 4, False))
        # Turns of one millisecond also keep the order they were saved in.
        self.assertEqual([m["content"] for m in messages[::2]], [f"hỏi {number}" for number in range(4)])
        for question, reply in zip(messages[::2], messages[1::2]):
            self.assertEqual((question["turnId"], reply["content"]), (reply["turnId"], question["content"].replace("hỏi", "đáp")))
        # Paging one message at a time through turns of one millisecond still visits each exactly once.
        seen, before = [], None
        while True:
            page, more = self.store.message_page("alice", "d" * 32, 1, before)
            seen = page + seen
            if not more:
                break
            before = parse_cursor(cursor_of(page[0]))
        self.assertEqual(seen, messages)

    def test_history_beyond_one_page_reaches_the_newest_turn(self):
        answer = {"reply": "đáp", "intent": "answer", "sources": []}
        for number in range(101):
            self.store.append_exchange("alice", "d" * 32, f"hỏi {number}", answer, number == 0)
        self.store.open_ticket("alice", "d" * 32, "gặp nhân viên")
        admin = bearer("admin", roles=("ADMIN",))
        with TestClient(create_app(self.settings, ChatStore(self.settings))) as client:
            path = f"{BASE}/conversations/{'d' * 32}/messages"
            first = client.get(path, headers=admin).json()
            self.assertEqual((first["messages"][-2]["content"], first["hasMore"]), ("hỏi 100", True))
            seen, cursor = first["messages"], first["nextCursor"]
            while cursor:
                page = client.get(path, headers=admin, params={"before": cursor, "limit": 64}).json()
                seen, cursor = page["messages"] + seen, page["nextCursor"]
        self.assertEqual([m["content"] for m in seen[::2]], [f"hỏi {number}" for number in range(101)])
        self.assertEqual(len({m["id"] for m in seen}), 202)

    def test_tickets_of_a_conversation(self):
        self.store.ensure_indexes()
        ticket, created = self.store.open_ticket("alice", "e" * 32, "gặp nhân viên")
        self.assertTrue(created)
        self.assertEqual(self.store.open_ticket("alice", "e" * 32, "alo")[0]["_id"], ticket["_id"])
        self.assertEqual(self.store.tickets.find_one({"_id": ticket["_id"]})["question"], "gặp nhân viên")
        self.assertNotEqual(self.store.open_ticket("bob", "e" * 32, "x")[0]["_id"], ticket["_id"])
        self.assertNotEqual(self.store.open_ticket("alice", "f" * 32, "x")[0]["_id"], ticket["_id"])
        self.assertEqual(self.store.ticket_owner("e" * 32), "alice")
        self.store.tickets.update_one({"_id": ticket["_id"]}, {"$set": {"status": "CLOSED"}})
        reopened, created = self.store.open_ticket("alice", "e" * 32, "lại")
        self.assertTrue(created)
        self.assertNotEqual(reopened["_id"], ticket["_id"])

    def test_concurrent_hand_offs_share_one_ticket(self):
        self.store.ensure_indexes()
        start = threading.Barrier(8)

        def hand_off(number):
            start.wait()
            return self.store.open_ticket("alice", "e" * 32, f"nhân viên đâu {number}")

        with ThreadPoolExecutor(8) as pool:
            results = list(pool.map(hand_off, range(8)))
        self.assertEqual(len({ticket["_id"] for ticket, _ in results}), 1)
        self.assertEqual(sum(created for _, created in results), 1)
        self.assertEqual(self.store.tickets.count_documents({"conversationId": "e" * 32}), 1)

    def test_tickets_opened_twice_before_the_unique_index_are_marked(self):
        older = {"userId": "alice", "conversationId": "e" * 32, "status": "OPEN", "question": "q"}
        self.store.tickets.insert_many([{**older, "_id": "A", "createdAt": datetime(2026, 10, 1)},
                                        {**older, "_id": "B", "createdAt": datetime(2026, 10, 2)}])
        self.store.ensure_indexes()
        self.assertEqual([(t["_id"], t["status"], t.get("duplicateOf")) for t in self.store.tickets.find().sort("_id")],
                         [("A", "OPEN", None), ("B", "DUPLICATE", "A")])
        self.assertEqual(self.store.open_ticket("alice", "e" * 32, "x")[0]["_id"], "A")

    def test_every_query_is_served_by_an_index(self):
        self.store.ensure_indexes()
        owned = {"conversationId": "d" * 32, "userId": "alice"}
        queries = {
            "knowledge by embedder": self.store.knowledge.find({"embedder": "hashing-v1-1024"}),
            "knowledge by source": self.store.knowledge.find({"source": "faq", "embedder": "hashing-v1-1024"}),
            "conversations": self.store.conversations.find({"userId": "alice"}).sort("updatedAt", DESCENDING),
            "messages": self.store.messages.find(owned).sort(MESSAGE_ORDER),
            "recent messages": self.store.messages.find(owned).sort([(key, DESCENDING) for key, _ in MESSAGE_ORDER]),
            "older messages": self.store.messages.find({**owned, "$or": [
                {"createdAt": {"$lt": datetime(2026, 10, 1)}}, {"createdAt": datetime(2026, 10, 1), "turnId": {"$lt": "f"}},
                {"createdAt": datetime(2026, 10, 1), "turnId": "f", "position": {"$lt": 1}}]}).sort(
                [(key, DESCENDING) for key, _ in MESSAGE_ORDER]),
            "own tickets": self.store.tickets.find({"userId": "alice"}).sort("createdAt", DESCENDING),
            "all tickets": self.store.tickets.find({}).sort("createdAt", DESCENDING),
            "open ticket": self.store.tickets.find({**owned, "status": "OPEN"}),
        }
        for name, cursor in queries.items():
            with self.subTest(query=name):
                used = stages(cursor.explain()["queryPlanner"]["winningPlan"])
                self.assertIn("IXSCAN", used)
                self.assertFalse(used & {"COLLSCAN", "SORT"}, used)

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
