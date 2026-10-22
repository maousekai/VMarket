"""Opt-in cold ASGI benchmark: 10,000 products, 1,000 interactions/user, four users.

Run python -m tests.benchmark_personalization against a freshly migrated
DB_NAME ending in _benchmark_test. Uses real PostgreSQL and isolated Redis DB 15.
"""
import json
import statistics
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import replace
from unittest.mock import patch

import jwt
import redis
from fastapi.testclient import TestClient
from psycopg.types.json import Jsonb

import store as store_module
from main import BASE, create_app
from settings import Settings
from store import Store
from tests.fixtures import snapshot


def main():
    settings = Settings.load()
    if not settings.db_name.endswith("_benchmark_test"):
        raise RuntimeError("Use a dedicated migrated database ending in _benchmark_test")
    settings = replace(settings, jwt_secret="test-only-secret-012345678901234567890123456789",
                       internal_key="test-internal-key")
    namespace = "benchmark-" + uuid.uuid4().hex
    users = [f"{namespace}-user-{i}" for i in range(4)]
    products = [snapshot(f"{namespace}-p{i}", name=f"Cotton shirt {i % 20}",
                         description="Cotton summer clothing", soldCount=i) for i in range(10000)]
    store = Store(settings)
    store.cache.close()
    store.cache = redis.Redis(host=settings.redis_host, port=settings.redis_port,
        password=settings.redis_password or None, db=15, decode_responses=True)
    samples = {"history_sql_ms": [], "peer_sql_ms": [], "seed_sql_ms": [],
               "candidate_fetch_ms": [], "ranking_ms": []}

    def timed(name, method):
        def call(*args, **kwargs):
            started = time.perf_counter()
            try:
                return method(*args, **kwargs)
            finally:
                samples[name].append((time.perf_counter() - started) * 1000)
        return call

    original_candidates = store.ranking_products
    def candidates(*args, **kwargs):
        with closing(original_candidates(*args, **kwargs)) as rows:
            while True:
                started = time.perf_counter()
                try:
                    row = next(rows)
                except StopIteration:
                    return
                finally:
                    samples["candidate_fetch_ms"].append((time.perf_counter() - started) * 1000)
                yield row

    try:
        store.start()
        with store.pool.connection() as conn, conn.cursor() as cursor:
            cursor.executemany("INSERT INTO item_feature(product_id, product_version, snapshot) VALUES (%s, 1, %s)",
                               [(p["id"], Jsonb(p)) for p in products])
            cursor.executemany("INSERT INTO user_event VALUES (%s, %s, %s, 'view', %s)",
                [(uuid.uuid4(), user, products[i]["id"], int(time.time() * 1000)) for user in users for i in range(1000)])
            conn.execute("ANALYZE item_feature")
            conn.execute("ANALYZE user_event")
        with TestClient(create_app(settings, store, run_consumer=False)) as client, \
                patch.object(store, "user_history", timed("history_sql_ms", store.user_history)), \
                patch.object(store, "collaborative_scores", timed("peer_sql_ms", store.collaborative_scores)), \
                patch.object(store, "products", timed("seed_sql_ms", store.products)), \
                patch.object(store, "ranking_products", candidates), \
                patch.object(store_module, "personalized", timed("ranking_ms", store_module.personalized)):
            def request(user):
                token = jwt.encode({"iss": "auth-service", "sub": user, "exp": int(time.time()) + 600},
                                   settings.jwt_secret, algorithm="HS256")
                started = time.perf_counter()
                response = client.get(BASE + "/for-you", headers={"Authorization": "Bearer " + token})
                assert response.status_code in (200, 503), response.text
                if response.status_code == 200:
                    assert len(response.json()["items"]) == 12
                return {"status": response.status_code, "ms": round((time.perf_counter() - started) * 1000, 1)}
            with ThreadPoolExecutor(max_workers=4) as executor:
                requests = list(executor.map(request, users))
        print(json.dumps({"catalog": 10000, "history_per_user": 1000, "concurrency": 4,
            "requests": requests, "503_rate": sum(r["status"] == 503 for r in requests) / 4,
            "stages": {name: {"total_ms": round(sum(values), 1),
                               "median_ms": round(statistics.median(values), 1) if values else None}
                       for name, values in samples.items()}}, indent=2))
    finally:
        # TestClient closes the Store; reopen only to remove this run's fixtures.
        cleanup = Store(settings)
        try:
            cleanup.start()
            with cleanup.pool.connection() as conn:
                conn.execute("DELETE FROM user_event WHERE user_id = ANY(%s)", (users,))
                conn.execute("DELETE FROM item_feature WHERE product_id = ANY(%s)", ([p["id"] for p in products],))
            for user in users:
                keys = list(store.cache.scan_iter(store.user_cache_prefix(user) + "*"))
                if keys:
                    store.cache.delete(*keys)
        finally:
            cleanup.close()
            store.close()


if __name__ == "__main__":
    main()
