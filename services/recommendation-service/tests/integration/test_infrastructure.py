"""Opt-in: DB_NAME must end in _test and already have Flyway migrations.
Uses isolated RabbitMQ topology and prefixed keys in Redis database 15.
Catalog HTTP responses are fixtures, not a live Product service.
"""
import json
import os
import statistics
import threading
import time
import unittest
import uuid
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock
from urllib.parse import urlsplit, parse_qs

import httpx
import pika
import psycopg
import redis
from fastapi.testclient import TestClient

from event_consumer import Consumer, connection_parameters
from main import BASE, create_app
from manage import seed
from schemas import Snapshot
from settings import Settings
from store import Store
from tests.fixtures import snapshot

ENABLED = os.environ.get("RECOMMENDATION_INTEGRATION") == "true"


def await_true(predicate, seconds=8):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise AssertionError("Timed out waiting for integration result")


@unittest.skipUnless(ENABLED, "Set RECOMMENDATION_INTEGRATION=true for an isolated migrated test database")
class InfrastructureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = {}
        cls.failure = 0
        cls.request_keys = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                cls.request_keys.append(self.headers.get("X-Internal-Api-Key"))
                parsed = urlsplit(self.path)
                if cls.failure:
                    self.send_error(cls.failure)
                    return
                if parsed.path == "/api/products/internal/search-snapshots":
                    page = int(parse_qs(parsed.query)["page"][0])
                    items = list(cls.rows.values())[page * 100:(page + 1) * 100]
                    body = {"items": items, "page": page, "hasMore": (page + 1) * 100 < len(cls.rows)}
                else:
                    body = cls.rows.get(parsed.path.rsplit("/", 1)[-1])
                    if body is None:
                        self.send_error(404)
                        return
                content = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(content)
            def log_message(self, *args):
                pass
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.server_thread.join()

    def setUp(self):
        base = Settings.load()
        if not base.db_name.endswith("_test"):
            raise RuntimeError("Integration tests require DB_NAME ending in _test")
        self.namespace = "recommendation-pr1-test-" + uuid.uuid4().hex
        self.settings = replace(base, exchange=self.namespace, queue=self.namespace + ".events",
                                product_url=f"http://127.0.0.1:{self.server.server_port}",
                                jwt_secret="test-only-secret-012345678901234567890123456789",
                                internal_key="test-internal-key")
        self.store = Store(self.settings)
        self.store.start()
        with self.store.pool.connection() as conn:
            conn.execute("TRUNCATE item_feature, processed_event")
        self.store.cache.close()
        self.store.cache = redis.Redis(host=base.redis_host, port=base.redis_port,
                                       password=base.redis_password or None, db=15, decode_responses=True)
        raw_get, raw_setex = self.store.cache.get, self.store.cache.setex
        self.store.cache.get = lambda key: raw_get(self.namespace + ":" + key)
        self.store.cache.setex = lambda key, ttl, value: raw_setex(self.namespace + ":" + key, ttl, value)
        self.consumer = Consumer(self.store)
        type(self).rows = {"p1": snapshot(), "p2": snapshot("p2", soldCount=10)}
        type(self).failure = 0

    def tearDown(self):
        self.consumer.close()
        connection = pika.BlockingConnection(connection_parameters(self.settings))
        try:
            channel = connection.channel()
            for queue_name in (self.settings.queue, self.settings.queue + ".dead"):
                try:
                    channel.queue_delete(queue=queue_name)
                except pika.exceptions.ChannelClosedByBroker:
                    channel = connection.channel()
            channel.exchange_delete(exchange=self.settings.exchange)
            channel.exchange_delete(exchange=self.settings.exchange + ".dlx")
        finally:
            connection.close()
        keys = list(self.store.cache.scan_iter(self.namespace + ":*"))
        if keys:
            self.store.cache.delete(*keys)
        self.store.close()

    def start_consumer(self):
        self.consumer.start()
        await_true(lambda: self.consumer.connected)

    def publish(self, key, payload=None, event_id=None, raw=None):
        connection = pika.BlockingConnection(connection_parameters(self.settings))
        try:
            channel = connection.channel()
            channel.confirm_delivery()
            data = raw or json.dumps({"eventId": event_id or str(uuid.uuid4()), "eventType": key,
                                      "timestamp": int(time.time() * 1000),
                                      "payload": payload or {"productId": "p1"}}).encode()
            channel.basic_publish(exchange=self.settings.exchange, routing_key=key, body=data,
                                  properties=pika.BasicProperties(delivery_mode=2), mandatory=True)
        finally:
            connection.close()

    def test_catalog_events_duplicates_tombstones_and_dead_letters(self):
        self.start_consumer()
        event_id = str(uuid.uuid4())
        self.publish("ProductCreated", event_id=event_id)
        await_true(lambda: self.store.seen(event_id))
        self.assertEqual(self.store.products()[0]["id"], "p1")
        self.assertEqual(type(self).request_keys[-1], "test-internal-key")
        self.publish("ProductCreated", event_id=event_id)
        type(self).rows["p1"] = snapshot(version=2, name="Updated")
        second = str(uuid.uuid4())
        self.publish("ProductUpdated", event_id=second)
        await_true(lambda: self.store.seen(second))
        self.assertEqual(self.store.products()[0]["name"], "Updated")
        type(self).rows["p1"] = {"id": "p1", "productVersion": 3, "catalogVisible": False,
                                 "deleted": True, "deletedAt": "2026-10-13T00:00:00Z"}
        third = str(uuid.uuid4())
        self.publish("ProductDeleted", event_id=third)
        await_true(lambda: self.store.seen(third))
        self.store.apply(Snapshot.model_validate(snapshot(version=1)))
        self.assertEqual(self.store.products(), [])
        with self.store.pool.connection() as conn:
            self.assertEqual(conn.execute("SELECT count(*) FROM processed_event").fetchone()[0], 3)
        self.publish("ProductCreated", raw=b"bad-json")
        connection = pika.BlockingConnection(connection_parameters(self.settings))
        try:
            channel = connection.channel()
            await_true(lambda: channel.queue_declare(queue=self.settings.queue + ".dead", passive=True).method.message_count == 1)
        finally:
            connection.close()

    def test_transient_http_failure_is_retried_before_ack(self):
        self.start_consumer()
        type(self).failure = 503
        event_id = str(uuid.uuid4())
        self.publish("ProductCreated", event_id=event_id)
        time.sleep(0.2)
        self.assertFalse(self.store.seen(event_id))
        type(self).failure = 0
        await_true(lambda: self.store.seen(event_id))

    def test_broker_reconnect_and_exhausted_retry(self):
        self.start_consumer()
        self.consumer.connection.add_callback_threadsafe(self.consumer.connection.close)
        await_true(lambda: not self.consumer.connected)
        await_true(lambda: self.consumer.connected)
        type(self).failure = 503
        event_id = str(uuid.uuid4())
        self.publish("ProductCreated", event_id=event_id)
        connection = pika.BlockingConnection(connection_parameters(self.settings))
        try:
            channel = connection.channel()
            await_true(lambda: channel.queue_declare(queue=self.settings.queue + ".dead", passive=True).method.message_count == 1)
            self.assertFalse(self.store.seen(event_id))
        finally:
            connection.close()

    def test_receipt_rollback_and_seed_rerun(self):
        event_id = str(uuid.uuid4())
        invalid = Snapshot.model_validate(snapshot()).model_copy(update={"productVersion": -1})
        with self.assertRaises(psycopg.errors.CheckViolation):
            self.store.apply(invalid, event_id)
        self.assertFalse(self.store.seen(event_id))
        self.assertEqual(seed(self.store), 2)
        self.store.apply(Snapshot.model_validate(snapshot(version=4, name="Newer")))
        self.assertEqual(seed(self.store), 2)
        self.assertEqual(next(p for p in self.store.products() if p["id"] == "p1")["name"], "Newer")
        self.start_consumer()
        with self.assertRaisesRegex(RuntimeError, "Stop"):
            seed(self.store)

    def test_api_cache_and_demo_latency(self):
        seed(self.store)
        samples = []
        with TestClient(create_app(self.settings, self.store, run_consumer=False)) as client:
            for _ in range(30):
                started = time.perf_counter()
                response = client.get(BASE + "/for-you?limit=2")
                samples.append((time.perf_counter() - started) * 1000)
                self.assertEqual(response.status_code, 200)
                self.assertEqual([p["id"] for p in response.json()["items"]], ["p2", "p1"])
            response = client.get(BASE + "/similar/p1")
            self.assertEqual([p["id"] for p in response.json()["items"]], ["p2"])
            self.store.apply(Snapshot.model_validate(snapshot("p2", version=2, catalogVisible=False)))
            self.assertEqual([p["id"] for p in client.get(BASE + "/for-you?limit=2").json()["items"]], ["p1"])
            actual_cache = self.store.cache
            self.store.cache = redis.Redis(host="127.0.0.1", port=1, socket_connect_timeout=0.1,
                                           socket_timeout=0.1, retry=redis.retry.Retry(redis.backoff.NoBackoff(), 0))
            self.assertEqual(client.get(BASE + "/for-you").status_code, 200)
            self.store.cache.close()
            self.store.cache = actual_cache
            actual_products = self.store.products
            self.store.products = Mock(side_effect=psycopg.OperationalError("offline"))
            self.assertEqual(client.get(BASE + "/for-you").status_code, 503)
            self.store.products = actual_products
        p95 = statistics.quantiles(samples, n=20)[18]
        print(f"Demo read p95: {p95:.1f} ms (30 sequential ASGI requests, 2 fixture products, local PostgreSQL/Redis)")
        self.assertLess(p95, 500)
