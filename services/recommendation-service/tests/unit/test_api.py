import json
import time
import unittest
from pathlib import Path
from unittest.mock import Mock

import jwt
import yaml
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from main import BASE, create_app
from store import Store
from tests.fixtures import settings, snapshot


class MemoryStore(Store):
    def __init__(self, rows):
        self.settings = settings()
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

    def products(self, category=None, ids=None):
        return [row for row in self.rows.values() if row["catalogVisible"] and not row["deleted"]
                and row["availableStock"] > 0 and (category is None or row["categoryId"] == category)
                and (ids is None or row["id"] in ids)]


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
                      "limit=1&limit=2", "category_id=", "category_id=a&category_id=b", "unexpected=1"):
            response = self.client.get(BASE + "/for-you?" + query)
            self.assertEqual(response.status_code, 400, query)
            self.assertEqual(response.json()["status"], 400)
        for seed in ("missing", "hidden", "stock"):
            self.assertEqual(self.client.get(BASE + "/similar/" + seed).status_code, 404)
        self.assertEqual(self.client.get(BASE + "/similar/seed?category_id=clothing").status_code, 400)
        self.assertEqual(self.client.get(BASE + "/similar/%20").status_code, 400)
        self.assertEqual(self.client.get(BASE + "/for-you?category_id=unknown").json()["items"], [])

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
        self.assertEqual(self.client.get(path).status_code, 404)
        self.store.cache.get.side_effect = ConnectionError("not a RedisError")
        self.assertEqual(self.client.get(BASE + "/for-you").status_code, 503)
        self.store.cache.get.side_effect = __import__("redis").ConnectionError()
        self.store.cache.setex.side_effect = __import__("redis").ConnectionError()
        self.assertEqual(self.client.get(BASE + "/for-you").status_code, 200)
        self.store.products = Mock(side_effect=RuntimeError("private connection detail"))
        result = self.client.get(BASE + "/for-you")
        self.assertEqual(result.status_code, 503)
        self.assertNotIn("private connection detail", result.text)

    def test_corrupt_cache_and_empty_catalog(self):
        self.client.get(BASE + "/for-you")
        key = next(iter(self.store.cached))
        for bad in ("null", "[1]", '["seed", "seed"]', "not-json", json.dumps(["x"] * 51)):
            self.store.cached[key] = bad
            self.assertEqual(self.client.get(BASE + "/for-you").status_code, 200)
        self.store.rows.clear()
        self.assertEqual(self.client.get(BASE + "/for-you").json()["items"], [])
