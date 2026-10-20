import json
import unittest
from unittest.mock import Mock

import psycopg
import redis

from event_consumer import Consumer, validate_event
from ranking import personalized
from store import Store
from tests.fixtures import settings, snapshot
from tests.unit.test_ranking_events import event


class PersonalizationTest(unittest.TestCase):
    def test_normalized_hybrid_content_only_and_popular_fill(self):
        seed = snapshot("seen", name="red cotton", description="", soldCount=0)
        products = [seed, snapshot("content", name="red cotton", description="", soldCount=1),
                    snapshot("hybrid", name="red", description="", soldCount=1),
                    snapshot("peer", name="phone", description="", categoryId="phones", soldCount=2),
                    snapshot("popular", name="table", description="", categoryId="furniture", soldCount=500)]
        self.assertEqual(personalized([seed], iter(products), {"peer": 2, "hybrid": 1}, {"seen"}),
                         ["hybrid", "peer", "content", "popular"])
        self.assertEqual(personalized([seed], iter(products), {}, {"seen"}),
                         ["content", "hybrid", "popular", "peer"])
        self.assertEqual(personalized([], iter(products), {}, {"seen"}),
                         ["popular", "peer", "content", "hybrid"])
        self.assertEqual(personalized([], iter([]), {}, set()), [])
        self.assertEqual(personalized([], iter(products), {"peer": 1}, {"seen", "peer"})[0], "popular")

    def test_behavior_contract_and_db_retry_without_catalog_fetch(self):
        payload = {"userId": "buyer", "productId": "pending", "action": "view"}
        for action in ("view", "add_to_cart", "purchase"):
            self.assertEqual(validate_event(event("UserBehaviorTracked", {**payload, "action": action}),
                                            "UserBehaviorTracked")[2], 1000)
        for changes in ({"userId": " "}, {"productId": "../"}, {"action": "purchase_cancelled"},
                        {"action": True}, {"other": "field"}):
            with self.assertRaises(ValueError):
                validate_event(event("UserBehaviorTracked", {**payload, **changes}), "UserBehaviorTracked")
        for timestamp in (-1, True, 2**63, "1000"):
            data = json.loads(event("UserBehaviorTracked", payload))
            data["timestamp"] = timestamp
            with self.assertRaises(ValueError):
                validate_event(json.dumps(data).encode(), "UserBehaviorTracked")
        store = Mock(settings=settings())
        store.seen.return_value = False
        store.apply.side_effect = [psycopg.OperationalError("offline"), True]
        consumer = Consumer(store)
        consumer.stop.wait = Mock(return_value=False)
        try:
            consumer.handle(event("UserBehaviorTracked", payload), "UserBehaviorTracked")
            store.fetch_snapshot.assert_not_called()
            self.assertEqual(store.apply.call_count, 2)
            self.assertEqual(store.apply.call_args.kwargs["behavior"], payload)
            self.assertEqual(store.apply.call_args.kwargs["occurred_at"], 1000)
            store.seen.return_value = True
            store.apply.reset_mock()
            consumer.handle(event("UserBehaviorTracked", payload), "UserBehaviorTracked")
            store.apply.assert_not_called()
        finally:
            consumer.close()

    def test_user_cache_invalidation_is_namespaced_bounded_and_best_effort(self):
        store = Store(settings())
        actual_cache = store.cache
        store.cache = Mock()
        try:
            prefix = store.user_cache_prefix("buyer")
            self.assertNotIn("buyer", prefix)
            self.assertNotEqual(prefix, store.user_cache_prefix("other"))
            store.cache.scan.side_effect = [(1, [prefix + "a"]), (0, [prefix + "b"])]
            store.invalidate_user("buyer")
            self.assertEqual(store.cache.delete.call_count, 2)
            self.assertEqual(store.cache.scan.call_args.kwargs["match"], prefix + "*")
            store.cache.scan.reset_mock()
            store.cache.scan.side_effect = None
            store.cache.scan.return_value = (1, [])
            with self.assertLogs(level="WARNING"):
                store.invalidate_user("buyer")
            self.assertLessEqual(store.cache.scan.call_count, 20)
            store.cache.scan.side_effect = redis.ConnectionError()
            with self.assertLogs(level="WARNING"):
                store.invalidate_user("buyer")
        finally:
            store.cache = actual_cache
            store.close()
