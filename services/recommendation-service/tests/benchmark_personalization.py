"""Opt-in Docker HTTP benchmark: 10,000 products, 1,000 interactions/user.

Build vmarket-recommendation:benchmark from the current Dockerfile, migrate a
local DB_NAME ending in _benchmark_test, then python -m tests.benchmark_personalization.
Uses Docker Desktop host networking, Redis DB 15, one worker, 2 CPUs and 512 MiB.
"""
import json
import math
import os
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from unittest.mock import Mock, patch

import httpx
import jwt
from psycopg.types.json import Jsonb

from main import BASE
from settings import Settings
from store import Store
from tests.fixtures import snapshot


def percentile(values, fraction):
    return round(sorted(values)[math.ceil(len(values) * fraction) - 1], 1) if values else None


def summarize(requests):
    successful = [r["ms"] for r in requests if r["status"] == 200]
    return {"requests": len(requests), "successes": len(successful),
            "503_rate": sum(r["status"] == 503 for r in requests) / len(requests),
            "transport_errors": sum(r["status"] == 0 for r in requests),
            "all_p95_ms": percentile([r["ms"] for r in requests], 0.95),
            "all_p99_ms": percentile([r["ms"] for r in requests], 0.99),
            "success_p95_ms": percentile(successful, 0.95), "success_p99_ms": percentile(successful, 0.99)}


def main():
    settings = Settings.load()
    if (not settings.db_name.endswith("_benchmark_test")
            or settings.db_host not in {"localhost", "127.0.0.1"}
            or settings.redis_host not in {"localhost", "127.0.0.1"}):
        raise RuntimeError("Use local dependencies and a dedicated migrated database ending in _benchmark_test")
    settings = replace(settings, jwt_secret="test-only-secret-012345678901234567890123456789",
                       internal_key="test-internal-key")
    namespace = "benchmark-" + uuid.uuid4().hex
    users = [f"{namespace}-user-{i}" for i in range(4)]
    products = [snapshot(f"{namespace}-p{i}", name=f"Cotton shirt {i % 20}",
                         description="Cotton summer clothing", soldCount=i) for i in range(10000)]
    store = Store(settings)
    store.cache.connection_pool.connection_kwargs["db"] = 15
    stop, resources, sampling_errors = threading.Event(), [], []
    sampler = None

    def docker(*args, **kwargs):
        return subprocess.run(["docker", *args], check=True, capture_output=True, text=True,
                              timeout=30, **kwargs).stdout.strip()

    def sample_resources():
        while not stop.is_set():
            try:
                row = json.loads(docker("stats", "--no-stream", "--format", "{{json .}}", namespace))
                resources.append({"cpu_percent": float(row["CPUPerc"].rstrip("%")),
                                  "memory_mib": float(row["MemPerc"].rstrip("%")) * 512 / 100})
            except (subprocess.SubprocessError, ValueError, KeyError) as error:
                sampling_errors.append(type(error).__name__)
                return
            stop.wait(1)

    def clear_cache():
        for user in users:
            keys = list(store.cache.scan_iter(store.user_cache_prefix(user) + "*"))
            if keys:
                store.cache.delete(*keys)

    try:
        store.start()
        with store.pool.connection() as conn:
            if conn.execute("SELECT EXISTS (SELECT 1 FROM item_feature) OR EXISTS (SELECT 1 FROM user_event)").fetchone()[0]:
                raise RuntimeError("Benchmark database must be empty for comparable results")
            with conn.cursor() as cursor:
                cursor.executemany("INSERT INTO item_feature(product_id, product_version, snapshot) VALUES (%s, 1, %s)",
                                   [(p["id"], Jsonb(p)) for p in products])
                cursor.executemany("INSERT INTO user_event VALUES (%s, %s, %s, 'view', %s)",
                    [(uuid.uuid4(), user, products[i]["id"], int(time.time() * 1000)) for user in users for i in range(1000)])
            conn.execute("ANALYZE item_feature")
            conn.execute("ANALYZE user_event")
        sql_ms = {}
        for label, method in (("history", store.user_history), ("peers", store.collaborative_scores)):
            with store.pool.connection() as conn:
                traced = Mock(wraps=conn)
                with patch.object(store.pool, "connection") as connection:
                    connection.return_value.__enter__.return_value = traced
                    method(users[0])
                query, args = traced.execute.call_args.args
                sql_ms[label] = conn.execute("EXPLAIN (ANALYZE, FORMAT JSON) " + query, args).fetchone()[0][0]["Execution Time"]
        environment = {**os.environ, "APP_ENV": "dev", "DB_HOST": "host.docker.internal",
            "DB_PORT": str(settings.db_port), "DB_NAME": settings.db_name, "DB_USERNAME": settings.db_user,
            "DB_PASSWORD": settings.db_password, "REDIS_HOST": "host.docker.internal",
            "REDIS_PORT": str(settings.redis_port), "REDIS_PASSWORD": settings.redis_password,
            "AUTH_JWT_SECRET": settings.jwt_secret, "INTERNAL_API_KEY": settings.internal_key,
            "RECOMMENDATION_DEPENDENCY_TIMEOUT_SECONDS": str(settings.timeout)}
        names = ["APP_ENV", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST",
                 "REDIS_PORT", "REDIS_PASSWORD", "AUTH_JWT_SECRET", "INTERNAL_API_KEY", "RECOMMENDATION_DEPENDENCY_TIMEOUT_SECONDS"]
        command = ("import uvicorn; from main import create_app; from settings import Settings; from store import Store; "
                   "s=Settings.load(); store=Store(s); store.cache.connection_pool.connection_kwargs['db']=15; "
                   "uvicorn.run(create_app(s,store,run_consumer=False),host='0.0.0.0',port=8101,access_log=False)")
        docker("run", "-d", "--rm", "--name", namespace, "--cpus=2", "--memory=512m",
               "-p", "127.0.0.1::8101", *[arg for name in names for arg in ("-e", name)],
               "vmarket-recommendation:benchmark", "python", "-c", command, env=environment)
        address = "http://" + docker("port", namespace, "8101/tcp")
        with httpx.Client(base_url=address, timeout=180, trust_env=False) as client:
            deadline = time.monotonic() + 30
            while True:
                try:
                    if client.get(BASE + "/health", timeout=2).status_code == 200:
                        break
                except httpx.TransportError:
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError("Benchmark container did not become ready")
                time.sleep(0.1)
            tokens = {user: jwt.encode({"iss": "auth-service", "sub": user, "exp": int(time.time()) + 1800},
                      settings.jwt_secret, algorithm="HS256") for user in users}
            def request(user):
                started = time.perf_counter()
                try:
                    response = client.get(BASE + "/for-you", headers={"Authorization": "Bearer " + tokens[user]})
                except httpx.TransportError:
                    return {"status": 0, "ms": (time.perf_counter() - started) * 1000}
                assert response.status_code in (200, 503), response.text
                if response.status_code == 200:
                    assert len(response.json()["items"]) == 12
                return {"status": response.status_code, "ms": (time.perf_counter() - started) * 1000}
            sampler = threading.Thread(target=sample_resources)
            sampler.start()
            cold = []
            with ThreadPoolExecutor(max_workers=4) as executor:
                for wave in range(5):
                    clear_cache()
                    cold.extend(executor.map(request, users))
                    print(f"Cold wave {wave + 1}/5 complete", file=sys.stderr, flush=True)
                for user in users:
                    assert request(user)["status"] == 200, "Warmup failed"
                warm = list(executor.map(request, users * 25))
        stop.set()
        sampler.join()
        assert resources and not sampling_errors, f"Missing resource evidence: {sampling_errors}"
        print(json.dumps({"catalog": 10000, "history_per_user": 1000, "concurrency": 4,
            "image": docker("inspect", namespace, "--format", "{{.Image}}"),
            "limits": {"cpus": 2, "memory_mib": 512, "workers": 1},
            "docker_host": json.loads(docker("info", "--format", '{"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}}}')),
            "sql_explain_execution_ms_before_load": sql_ms, "cold": summarize(cold), "warm": summarize(warm),
            "resource_samples": len(resources), "cpu_percent_p95": percentile([r["cpu_percent"] for r in resources], 0.95),
            "cpu_percent_max": max(r["cpu_percent"] for r in resources),
            "sampled_memory_mib_max": round(max(r["memory_mib"] for r in resources), 1)}, indent=2))
    finally:
        stop.set()
        if sampler:
            sampler.join()
        subprocess.run(["docker", "rm", "-f", namespace], capture_output=True, timeout=30)
        try:
            with store.pool.connection() as conn:
                conn.execute("DELETE FROM user_event WHERE user_id = ANY(%s)", (users,))
                conn.execute("DELETE FROM item_feature WHERE product_id = ANY(%s)", ([p["id"] for p in products],))
            clear_cache()
        finally:
            store.close()


if __name__ == "__main__":
    main()
