import json
import queue
import unittest
import uuid
from unittest.mock import Mock

import httpx
import psycopg
from pydantic import ValidationError

from event_consumer import Consumer, declare_topology, validate_event
from ranking import content_score, price_distance, similar, tokens
from schemas import Snapshot
from settings import Settings
from tests.fixtures import settings, snapshot


def event(key="ProductCreated", payload=None):
    return json.dumps({"eventId": str(uuid.uuid4()), "eventType": key, "timestamp": 1000,
                      "payload": {"productId": "p1"} if payload is None else payload}).encode()


class RankingAndEventTest(unittest.TestCase):
    def test_vietnamese_content_and_price_ties(self):
        seed = snapshot(name="Đầm ĐẸP", description="")
        self.assertEqual(tokens(seed), {"dam", "dep"})
        same = snapshot("same", name="dam dep", description="", minPrice=110000)
        far = snapshot("far", name="dam dep", description="", minPrice=200000, soldCount=100)
        unrelated = snapshot("unrelated", name="Phone", description="", categoryId="phones")
        self.assertEqual(content_score(seed, same), 1)
        self.assertEqual(content_score(seed, unrelated), 0)
        self.assertEqual([p["id"] for p in similar(seed, [seed, far, same, unrelated])], ["same", "far", "unrelated"])
        self.assertEqual(content_score({"name": "", "description": "", "categoryId": None},
                                       {"name": "", "description": "", "categoryId": None}), 0)
        self.assertEqual(price_distance({"minPrice": 0}, {"minPrice": 0}), 0)
        self.assertEqual(price_distance({"minPrice": None}, same), float("inf"))
        sibling = snapshot("sibling", name="Phone", description="", categoryPath=["fashion", "shoes"])
        seed["categoryPath"] = ["fashion", "clothing"]
        self.assertGreater(content_score(seed, sibling), content_score(seed, unrelated))

    def test_snapshot_validation_and_settings(self):
        Snapshot.model_validate(snapshot())
        for changes in ({"currency": "USD"}, {"availableStock": -1}, {"variantPrices": [False]},
                        {"productVersion": -1}, {"minPrice": 1}, {"name": None}):
            with self.assertRaises(ValidationError):
                Snapshot.model_validate(snapshot(**changes))
        Snapshot.model_validate({"id": "p1", "productVersion": 2, "catalogVisible": False,
                                 "deleted": True, "deletedAt": "2026-10-01T00:00:00Z"})
        for changes in ({"timeout": float("inf")}, {"timeout": 0}, {"db_port": 0},
                        {"product_url": "http://user:password@host"}, {"app_env": "prod"}):
            with self.assertRaises(ValueError):
                settings(**changes)
        with self.assertRaises(ValueError):
            Settings().require_secrets()
        loaded = Settings.load(env_file="/no-file", environ={"DB_PORT": "5439", "AUTH_JWT_SECRET": "x" * 32})
        self.assertEqual(loaded.db_port, 5439)

    def test_envelopes_and_queue_compatibility(self):
        validate_event(event(), "ProductCreated")
        for body, key in ((event(), "ProductDeleted"), (b"[]", "ProductCreated"),
                          (event(payload={"productId": "../"}), "ProductCreated"),
                          (event("UserBehaviorTracked"), "UserBehaviorTracked"),
                          (event("OrderPlaced", {"orderId": "o1", "items": []}), "OrderPlaced")):
            with self.assertRaises((ValueError, KeyError, TypeError)):
                validate_event(body, key)
        channel = Mock()
        declare_topology(channel, settings())
        call = channel.queue_declare.call_args_list[0].kwargs
        self.assertEqual(call["queue"], "recommendation.events")
        self.assertEqual(call["arguments"]["x-dead-letter-routing-key"], "recommendation.events.dead")
        self.assertEqual(channel.queue_bind.call_count, 6)

    def test_retries_duplicate_noop_and_order_noop(self):
        store = Mock(settings=settings())
        store.seen.return_value = False
        consumer = Consumer(store)
        consumer.stop.wait = Mock(return_value=False)
        try:
            store.fetch_snapshot.side_effect = [httpx.ConnectError("down"), psycopg.OperationalError("down"), Snapshot.model_validate(snapshot())]
            consumer.handle(event(), "ProductCreated")
            self.assertEqual(store.fetch_snapshot.call_count, 3)
            self.assertEqual(consumer.stop.wait.call_args_list[1].args, (2,))
            store.seen.return_value = True
            store.apply.reset_mock()
            consumer.handle(event(), "ProductCreated")
            store.apply.assert_not_called()
            store.seen.return_value = False
            store.fetch_snapshot.reset_mock()
            store.seen.reset_mock()
            for key in ("OrderPlaced", "OrderStatusChanged"):
                consumer.handle(b"irrelevant or malformed order body", key)
            store.fetch_snapshot.assert_not_called()
            store.apply.assert_not_called()
            store.seen.assert_not_called()
        finally:
            consumer.close()

    def test_ack_nack_and_shutdown(self):
        store = Mock(settings=settings())
        store.seen.return_value = False
        consumer = Consumer(store)
        callbacks = queue.Queue()
        consumer.connection = Mock(is_open=True)
        consumer.connection.add_callback_threadsafe.side_effect = callbacks.put
        channel = Mock(is_open=True)
        method = Mock(routing_key="ProductCreated", delivery_tag=7)
        try:
            consumer.on_message(channel, method, None, event())
            callbacks.get(timeout=2)()
            store.apply.assert_called_once()
            channel.basic_ack.assert_called_once_with(delivery_tag=7)
            consumer.on_message(channel, method, None, b"bad-json")
            callbacks.get(timeout=2)()
            channel.basic_nack.assert_called_once_with(delivery_tag=7, requeue=False)
            store.apply.side_effect = ValueError("commit failed")
            consumer.on_message(channel, method, None, event())
            callbacks.get(timeout=2)()
            self.assertEqual(channel.basic_ack.call_count, 1)
            self.assertEqual(channel.basic_nack.call_count, 2)
            method.routing_key = "OrderPlaced"
            consumer.on_message(channel, method, None, b"not-json")
            callbacks.get(timeout=2)()
            self.assertEqual(channel.basic_ack.call_count, 2)
            self.assertEqual(channel.basic_nack.call_count, 2)
            consumer.stop.set()
            with self.assertRaises(InterruptedError):
                consumer.handle(event(), "ProductCreated")
        finally:
            consumer.close()

    def test_dead_letter_monitor(self):
        consumer = Consumer(Mock(settings=settings()))
        consumer.channel = Mock()
        consumer.channel.queue_declare.return_value.method.message_count = 2
        consumer.connection = Mock(is_open=False)
        try:
            with self.assertLogs(level="WARNING") as logs:
                consumer.check_dead_letters()
            self.assertEqual(consumer.dead_letters, 2)
            self.assertIn("recommendation_dead_letters_pending count=2", logs.output[0])
            consumer.connection.call_later.assert_called_once_with(30, consumer.check_dead_letters)
        finally:
            consumer.close()
