import json
import asyncio
import time
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock, patch

import jwt
import httpx
import yaml
from anyio import to_thread
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator
from starlette.exceptions import HTTPException

from main import BASE, create_app
from store import RANK_FIELDS, Store
from tests.fixtures import settings, snapshot


class MemoryStore(Store):
    def __init__(self, rows):
        self.settings = settings()
        self.rank_locks = [threading.Lock() for _ in range(32)]
        self.cache_retry_at = 0
        self.rows = {row["id"]: row for row in rows}
        self.cached = {}
        self.cache = Mock()
        self.cache.get.side_effect = self.cached.get
        self.cache.setex.side_effect = lambda key, ttl, value: self.cached.__setitem__(key, value)
        self.started = self.closed = False

    def start(self):
        self.started = True

    def close(self):
        self.closed = True

    def health(self):
        return {"postgres": "ready", "redis": "ready", "catalog": {"items": len(self.rows), "lastRefreshAt": None}}

    def products(self, category=None, ids=None, *, in_stock=True):
        return [row for row in self.rows.values() if row["catalogVisible"] and not row["deleted"]
                and (not in_stock or row["availableStock"] > 0) and (category is None or category in row["categoryPath"])
                and (ids is None or row["id"] in ids)]

    def ranking_products(self, category=None, *, popularity=False):
        return ({field: row[field] for field in RANK_FIELDS} for row in self.products(category))


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore([snapshot("seed"), snapshot("near"), snapshot("popular", soldCount=20),
                                  snapshot("hidden", catalogVisible=False), snapshot("stock", availableStock=0),
                                  snapshot("other", categoryId="shoes", soldCount=100)])
        self.client = TestClient(create_app(self.store.settings, self.store, run_consumer=False))
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.assertTrue(self.store.closed)

    def token(self, **claims):
        payload = {"iss": "auth-service", "sub": "buyer", "exp": int(time.time()) + 60}
        payload.update(claims)
        return jwt.encode(payload, self.store.settings.jwt_secret, algorithm="HS256")

    def test_fallback_auth_contract_and_lifespan(self):
        self.assertTrue(self.store.started)
        guest = self.client.get(BASE + "/for-you?category_id=clothing&limit=2", headers={"X-User-Id": "forged"})
        buyer = self.client.get(BASE + "/for-you?category_id=clothing&limit=2",
                                headers={"Authorization": "Bearer " + self.token()})
        self.assertEqual(guest.json()["user_id"], "")
        self.assertEqual(buyer.json()["user_id"], "buyer")
        self.assertEqual(guest.json()["items"], buyer.json()["items"])
        self.assertEqual([p["id"] for p in guest.json()["items"]], ["popular", "near"])
        contract = yaml.safe_load((Path(__file__).resolve().parents[4] / "docs/openapi/recommendation-service.yaml").read_text())
        schema = {"$ref": "#/components/schemas/Product", "components": contract["components"]}
        for item in guest.json()["items"]:
            Draft202012Validator(schema).validate(item)
            self.assertNotIn("productVersion", item)
        self.assertEqual(self.client.get(BASE + "/health").json()["rabbitmq"], "unavailable")

    def test_auth_rejects_forged_expired_wrong_issuer_and_missing_claims(self):
        tokens = ["bad", self.token(exp=1), self.token(iss="other"), self.token(sub=" "),
                  jwt.encode({"iss": "auth-service", "sub": "buyer"}, self.store.settings.jwt_secret, algorithm="HS256"),
                  jwt.encode({"iss": "auth-service", "sub": "buyer", "exp": time.time() + 60},
                             "wrong-test-secret-01234567890123456789", algorithm="HS256"),
                  jwt.encode({"iss": "auth-service", "sub": "buyer", "exp": time.time() + 60},
                             self.store.settings.jwt_secret, algorithm="HS384")]
        for token in tokens:
            response = self.client.get(BASE + "/for-you", headers={"Authorization": "Bearer " + token})
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.json()["error"]["code"], "UNAUTHORIZED")
        self.assertEqual(self.client.get(BASE + "/for-you", headers={"Authorization": "Basic nope"}).status_code, 401)
        self.assertEqual(self.client.get(BASE + "/for-you",
            headers=[("Authorization", "Bearer " + self.token()), ("Authorization", "Bearer " + self.token())]).status_code, 401)

    def test_invalid_queries_and_seeds(self):
        for query in ("user_id=another", "limit=0", "limit=51", "limit=-1", "limit=1.5", "limit=１２",
                      "limit=1&limit=2", "category_id=", "category_id=a&category_id=b"):
            response = self.client.get(BASE + "/for-you?" + query)
            self.assertEqual(response.status_code, 400, query)
            self.assertEqual(response.json()["status"], 400)
        for seed in ("missing", "hidden"):
            self.assertEqual(self.client.get(BASE + "/similar/" + seed).status_code, 404)
        self.assertEqual(self.client.get(BASE + "/similar/seed?category_id=clothing").status_code, 400)
        self.assertEqual(self.client.get(BASE + "/similar/%20").status_code, 400)
        self.assertEqual(self.client.get(BASE + "/for-you?category_id=unknown").json()["items"], [])
        self.assertEqual(self.client.get(BASE + "/for-you?_=123&unexpected=1").status_code, 200)
        alternatives = self.client.get(BASE + "/similar/stock").json()["items"]
        self.assertTrue(alternatives)
        self.assertTrue(all(p["availableStock"] > 0 and p["id"] != "stock" for p in alternatives))

    def test_similarity_cache_freshness_and_dependency_failures(self):
        path = BASE + "/similar/seed?limit=2"
        response = self.client.get(path)
        self.assertEqual([p["id"] for p in response.json()["items"]], ["popular", "near"])
        self.store.rows["near"]["catalogVisible"] = False
        self.store.rows["popular"]["name"] = "Updated name"
        items = self.client.get(path).json()["items"]
        self.assertEqual([p["id"] for p in items], ["popular"])
        self.assertEqual(items[0]["name"], "Updated name")
        self.store.rows["seed"]["availableStock"] = 0
        self.assertEqual(self.client.get(path).status_code, 200)
        self.store.cache.get.side_effect = ConnectionError("not a RedisError")
        self.assertEqual(self.client.get(BASE + "/for-you").status_code, 503)
        self.store.cache.get.side_effect = __import__("redis").ConnectionError()
        self.store.cache.setex.side_effect = __import__("redis").ConnectionError()
        self.assertEqual(self.client.get(BASE + "/for-you").status_code, 200)
        self.store.products = Mock(side_effect=RuntimeError("private connection detail"))
        result = self.client.get(BASE + "/for-you")
        self.assertEqual(result.status_code, 503)
        self.assertNotIn("private connection detail", result.text)

    def test_parent_categories_cache_sharing_and_concurrent_misses(self):
        self.store.rows = {str(i): snapshot(str(i), soldCount=i, categoryPath=["fashion", "clothing"])
                           for i in range(80)}
        self.store.ranking_products = Mock(wraps=self.store.ranking_products)
        barrier = threading.Barrier(6)
        def request(limit):
            barrier.wait()
            return self.store.recommendations(category="fashion", limit=limit)
        with ThreadPoolExecutor(max_workers=6) as executor:
            results = list(executor.map(request, [1, 2, 10, 12, 20, 50]))
        self.assertEqual([len(r) for r in results], [1, 2, 10, 12, 20, 50])
        self.assertEqual(results[0][0]["id"], "79")
        self.store.ranking_products.assert_called_once()
        self.store.cache.setex.assert_called_once()
        self.assertEqual(len(json.loads(next(iter(self.store.cached.values())))), 50)

    def test_clock_skew_health_and_framework_errors(self):
        @self.client.app.get("/validation-test")
        def validation_test(count: int):
            return count
        self.assertEqual(self.client.get("/validation-test").status_code, 400)
        self.assertEqual(self.client.get("/validation-test").json()["status"], 400)
        self.assertEqual(self.client.get("/validation-test").json()["error"]["code"], "INVALID_QUERY")
        @self.client.app.get("/http-error-test/{status}")
        def http_error_test(status: int):
            raise HTTPException(status, "Test error", headers={"Retry-After": "1"})
        for status, code in ((400, "INVALID_QUERY"), (413, "PAYLOAD_TOO_LARGE"), (429, "TOO_MANY_REQUESTS"), (499, "HTTP_ERROR")):
            response = self.client.get(f"/http-error-test/{status}")
            self.assertEqual(response.status_code, status)
            self.assertEqual(response.json()["error"]["code"], code)
            self.assertEqual(response.headers["Retry-After"], "1")
        for expiry, expected in ((time.time() - 10, 200), (time.time() - 60, 401)):
            response = self.client.get(BASE + "/for-you", headers={"Authorization": "Bearer " + self.token(exp=expiry)})
            self.assertEqual(response.status_code, expected)
        self.store.health = Mock(return_value={"postgres": "unavailable", "redis": "ready",
                                             "catalog": {"items": None, "lastRefreshAt": None}})
        response = self.client.get(BASE + "/health")
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["status"], "DOWN")
        self.store.health.return_value["postgres"] = "ready"
        self.store.health.return_value["redis"] = "unavailable"
        self.assertEqual(self.client.get(BASE + "/health").status_code, 200)
        for response, code in ((self.client.get(BASE + "/unknown"), "NOT_FOUND"),
                               (self.client.post(BASE + "/for-you"), "METHOD_NOT_ALLOWED")):
            self.assertEqual(response.json()["error"]["code"], code)
            self.assertEqual(response.json()["status"], response.status_code)
            self.assertNotIn("detail", response.json())

    def test_redis_outage_backoff(self):
        self.store.cache.get.side_effect = __import__("redis").ConnectionError()
        for _ in range(3):
            self.assertEqual(self.client.get(BASE + "/for-you").status_code, 200)
        self.store.cache.get.assert_called_once()
        self.store.cache.setex.assert_not_called()
        self.store.cache_retry_at = 0
        self.store.cache.get.side_effect = self.store.cached.get
        self.assertEqual(self.client.get(BASE + "/for-you").status_code, 200)
        self.store.cache.setex.assert_called_once()

    def test_cache_hits_bypass_held_locks_and_misses_time_out(self):
        self.store.settings = replace(self.store.settings, timeout=0.03)
        self.store.recommendations()
        self.store.recommendations(category="clothing")
        self.store.ranking_products = Mock(wraps=self.store.ranking_products)
        for lock in self.store.rank_locks:
            lock.acquire()
        executor = ThreadPoolExecutor(max_workers=1)
        try:
            for query, status in (("", 200), ("?category_id=clothing", 200), ("?category_id=shoes", 503)):
                response = executor.submit(self.client.get, BASE + "/for-you" + query).result(timeout=1)
                self.assertEqual(response.status_code, status)
            self.store.ranking_products.assert_not_called()
        finally:
            for lock in self.store.rank_locks:
                lock.release()
            executor.shutdown(wait=True)
        self.assertEqual(self.client.get(BASE + "/for-you?category_id=shoes").status_code, 200)

    def test_ranking_failure_closes_iterator_and_releases_lock(self):
        for path, ranker in (("/for-you", "popular"), ("/similar/seed", "similar")):
            closed = threading.Event()
            def candidates():
                try:
                    yield snapshot("near")
                finally:
                    closed.set()
            stream = candidates()
            self.store.ranking_products = Mock(return_value=stream)
            def fail(*args):
                next(args[-1])
                raise ValueError("Ranking failed")
            with patch("store." + ranker, side_effect=fail):
                self.assertEqual(self.client.get(BASE + path).status_code, 503)
            self.assertTrue(closed.is_set())
            for lock in self.store.rank_locks:
                self.assertTrue(lock.acquire(blocking=False))
                lock.release()

    def test_health_does_not_wait_for_synchronous_request_workers(self):
        started, release = threading.Event(), threading.Event()
        def slow_ranking(**options):
            started.set()
            release.wait(timeout=5)
            return []
        self.store.recommendations = slow_ranking
        async def exercise():
            limiter = to_thread.current_default_thread_limiter()
            original = limiter.total_tokens
            limiter.total_tokens = 1
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.client.app), base_url="http://test") as client:
                request = asyncio.create_task(client.get(BASE + "/for-you"))
                try:
                    self.assertTrue(await asyncio.to_thread(started.wait, 2))
                    health = await asyncio.wait_for(client.get(BASE + "/health"), timeout=1)
                    self.assertEqual(health.status_code, 200)
                finally:
                    release.set()
                    await request
                    limiter.total_tokens = original
        asyncio.run(exercise())

    def test_health_caches_statistics_but_checks_postgres_each_time(self):
        store = Store(settings())
        conn = Mock()
        conn.execute.return_value.fetchone.return_value = (2, None)
        try:
            with patch.object(store.pool, "connection") as connection, patch.object(store.cache, "ping"):
                connection.return_value.__enter__.return_value = conn
                self.assertEqual(store.health()["catalog"]["items"], 2)
                conn.execute.reset_mock()
                self.assertEqual(store.health()["postgres"], "ready")
                conn.execute.assert_called_once_with("SELECT 1")
                store.catalog_retry_at = 0
                conn.execute.return_value.fetchone.return_value = (3, None)
                self.assertEqual(store.health()["catalog"]["items"], 3)
                connection.side_effect = RuntimeError("offline")
                result = store.health()
                self.assertEqual(result["postgres"], "unavailable")
                self.assertIsNone(result["catalog"]["items"])
        finally:
            store.close()

    def test_corrupt_cache_and_empty_catalog(self):
        self.client.get(BASE + "/for-you")
        key = next(iter(self.store.cached))
        for bad in ("null", "[1]", '["seed", "seed"]', "not-json", json.dumps(["x"] * 51)):
            self.store.cached[key] = bad
            self.assertEqual(self.client.get(BASE + "/for-you").status_code, 200)
        self.store.rows.clear()
        self.assertEqual(self.client.get(BASE + "/for-you").json()["items"], [])
