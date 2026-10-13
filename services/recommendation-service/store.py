"""PostgreSQL owns the projection; Redis is an optional ranking cache."""
import hashlib
import json
import logging
from urllib.parse import quote

import httpx
import redis
from redis.backoff import NoBackoff
from redis.retry import Retry
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from ranking import popular, similar
from schemas import PUBLIC_FIELDS, RecommendationError, Snapshot

ELIGIBLE = """snapshot->>'catalogVisible' = 'true' AND snapshot->>'deleted' = 'false'
              AND (snapshot->>'availableStock')::bigint > 0"""


class Store:
    def __init__(self, settings):
        self.settings = settings
        self.pool = ConnectionPool(open=False, min_size=1, max_size=8, timeout=settings.timeout,
            kwargs={"host": settings.db_host, "port": settings.db_port, "dbname": settings.db_name,
                    "user": settings.db_user, "password": settings.db_password,
                    "connect_timeout": max(1, int(settings.timeout)),
                    "options": f"-c statement_timeout={int(settings.timeout * 1000)}"})
        self.cache = redis.Redis(host=settings.redis_host, port=settings.redis_port,
            password=settings.redis_password or None, decode_responses=True,
            socket_connect_timeout=min(settings.timeout, 0.2), socket_timeout=min(settings.timeout, 0.2),
            retry=Retry(NoBackoff(), 0))
        self.client = httpx.Client(base_url=settings.product_url.rstrip("/"),
            headers={"X-Internal-Api-Key": settings.internal_key}, timeout=settings.timeout,
            follow_redirects=False)

    def start(self):
        self.pool.open()
        self.pool.wait(timeout=self.settings.timeout)
        try:
            with self.pool.connection() as conn:
                conn.execute("SELECT product_id, product_version, snapshot FROM item_feature LIMIT 0")
                conn.execute("SELECT event_id FROM processed_event LIMIT 0")
        except Exception:
            raise RuntimeError("Recommendation schema unavailable; run manage.py migrate first") from None

    def close(self):
        self.client.close()
        self.cache.close()
        self.pool.close()

    def seen(self, event_id):
        with self.pool.connection() as conn:
            return conn.execute("SELECT 1 FROM processed_event WHERE event_id = %s", (event_id,)).fetchone() is not None

    def fetch_snapshot(self, product_id):
        response = self.client.get("/api/products/internal/search-snapshots/" + quote(product_id, safe=""))
        response.raise_for_status()
        snapshot = Snapshot.model_validate(response.json())
        if snapshot.id != product_id:
            raise ValueError("Snapshot product ID mismatch")
        return snapshot

    def apply(self, snapshot=None, event_id=None):
        with self.pool.connection() as conn:
            if event_id is not None:
                receipt = conn.execute("""INSERT INTO processed_event(event_id) VALUES (%s)
                    ON CONFLICT DO NOTHING RETURNING event_id""", (event_id,)).fetchone()
                if receipt is None:
                    return False
            if snapshot is not None:
                conn.execute("""INSERT INTO item_feature(product_id, product_version, snapshot)
                    VALUES (%s, %s, %s) ON CONFLICT (product_id) DO UPDATE
                    SET product_version = EXCLUDED.product_version, snapshot = EXCLUDED.snapshot,
                        refreshed_at = CURRENT_TIMESTAMP
                    WHERE item_feature.product_version < EXCLUDED.product_version""",
                    (snapshot.id, snapshot.productVersion, Jsonb(snapshot.model_dump())))
        return True

    def products(self, category=None, ids=None):
        query = "SELECT snapshot FROM item_feature WHERE " + ELIGIBLE
        args = []
        if category is not None:
            query += " AND snapshot->>'categoryId' = %s"
            args.append(category)
        if ids is not None:
            query += " AND product_id = ANY(%s)"
            args.append(ids)
        with self.pool.connection() as conn:
            return [row[0] for row in conn.execute(query, args).fetchall()]

    def recommendations(self, *, category=None, seed_id=None, limit=12):
        seed = None
        if seed_id is not None:
            seeds = self.products(ids=[seed_id])
            if not seeds:
                raise RecommendationError(404, "PRODUCT_NOT_FOUND", "Product does not exist")
            seed = seeds[0]
        key = "recommendation:v1:" + hashlib.sha256(
            json.dumps([category, seed_id, limit], ensure_ascii=False).encode()).hexdigest()
        ids = None
        try:
            raw = self.cache.get(key)
            if raw is not None:
                ids = json.loads(raw)
                if (not isinstance(ids, list) or len(ids) > limit
                        or any(not isinstance(i, str) for i in ids) or len(set(ids)) != len(ids)
                        or seed_id in ids):
                    ids = None
        except (redis.RedisError, ValueError, TypeError):
            logging.warning("recommendation_cache_read_failed")
        if ids is None:
            products = self.products(category)
            # shortcut: rank the demo catalog in memory; add bounded retrieval when catalog size requires it.
            ranked = similar(seed, products) if seed is not None else popular(products)
            ids = [item["id"] for item in ranked[:limit]]
            try:
                self.cache.setex(key, 60, json.dumps(ids))
            except redis.RedisError:
                logging.warning("recommendation_cache_write_failed")
        # Cache never decides visibility or response fields.
        current = {item["id"]: item for item in self.products(category, ids)}
        return [{field: current[i].get(field) for field in PUBLIC_FIELDS} for i in ids if i in current]

    def health(self):
        result = {"postgres": "unavailable", "redis": "unavailable",
                  "catalog": {"items": None, "lastRefreshAt": None}}
        try:
            with self.pool.connection() as conn:
                count, refreshed = conn.execute(
                    "SELECT count(*) FILTER (WHERE " + ELIGIBLE + "), max(refreshed_at) FROM item_feature").fetchone()
            result["postgres"] = "ready"
            result["catalog"] = {"items": count, "lastRefreshAt": refreshed.isoformat() if refreshed else None}
        except Exception as error:
            logging.warning("recommendation_health_postgres_failed type=%s", type(error).__name__)
        try:
            self.cache.ping()
            result["redis"] = "ready"
        except redis.RedisError:
            pass
        return result
