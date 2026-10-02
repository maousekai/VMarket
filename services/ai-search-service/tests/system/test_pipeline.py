"""Isolated infrastructure smoke: generated images, HTTP snapshot stub, real ES/MinIO/Rabbit.

Not a real-photo quality benchmark or a substitute for catalog authorization checks.
The only mutated resources are this run's unique bucket/index/queues/users.
"""
import io
import json
import os
import secrets
import subprocess
import threading
import time
import unittest
import uuid
from dataclasses import replace
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import boto3
import pika
from botocore.config import Config
from botocore.exceptions import ClientError
from dotenv import dotenv_values
from elasticsearch import Elasticsearch
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

import event_consumer
import manage
from event_consumer import Workers, declare_topology
from images import ImageEncoder
from main import create_app
from search import SearchStore, index_definition
from settings import ROOT, Settings


def wait_for(check, seconds=60):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if check(): return
        time.sleep(0.2)
    raise AssertionError("Synchronization deadline exceeded")


class PipelineChecks(unittest.TestCase):
    def test_generated_image_event_pipeline(self):
        suffix = uuid.uuid4().hex
        index = "vmarket-products-v1-test-system-" + suffix
        alias = "test-search-" + suffix
        bucket = "search-test-" + suffix
        queue = "search-test-" + suffix
        exchange = "search-test-" + suffix
        reader = "test-reader-" + suffix[:12]
        read_secret = secrets.token_hex(20)
        private = dotenv_values(ROOT.parent.parent / ".env")
        admin_key = private.get("MINIO_ROOT_USER", "minioadmin")
        admin_secret = private.get("MINIO_ROOT_PASSWORD", "minioadmin")
        s3 = boto3.client("s3", endpoint_url="http://localhost:9000", aws_access_key_id=admin_key,
            aws_secret_access_key=admin_secret, region_name="us-east-1", config=Config(proxies={}, s3={"addressing_style": "path"}))
        # The bundled client reads admin credentials inside the container; never print them.
        container = os.getenv("MINIO_TEST_CONTAINER", "vmarket-minio-1")
        def mc(script, **values):
            args = ["docker", "exec"]
            for name, value in values.items(): args.extend(["-e", name + "=" + value])
            args.extend([container, "sh", "-c", script])
            result = subprocess.run(args, capture_output=True, text=True)
            if result.returncode: raise AssertionError("MinIO test user/policy operation failed")
        data = io.BytesIO()
        image = Image.new("RGB", (256, 256), "white")
        ImageDraw.Draw(image).rectangle((40, 20, 210, 230), fill="red")
        image.save(data, format="PNG")
        image.close()
        s3.create_bucket(Bucket=bucket)
        policy = json.dumps({"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": ["s3:GetObject"], "Resource": [f"arn:aws:s3:::{bucket}/products/*"]}]})
        mc('mc alias set it http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; '
           'mc admin user add it "$TEST_READER" "$TEST_SECRET" >/dev/null; '
           'printf "%s" "$TEST_POLICY" > /tmp/"$TEST_READER".json; '
           'mc admin policy create it "$TEST_READER" /tmp/"$TEST_READER".json >/dev/null; '
           'mc admin policy attach it "$TEST_READER" --user "$TEST_READER" >/dev/null; rm /tmp/"$TEST_READER".json',
           TEST_READER=reader, TEST_SECRET=read_secret, TEST_POLICY=policy)
        urls = []
        for number in range(9):
            key = f"products/{suffix}-{number}.png"
            s3.put_object(Bucket=bucket, Key=key, Body=data.getvalue(), ContentType="image/png")
            urls.append(f"http://localhost:9000/{bucket}/{key}")
        snapshots = {"p1": {"id": "p1", "productVersion": 0, "catalogVisible": True, "deleted": False, "deletedAt": None,
            "shopId": "shop", "name": "Áo thun", "description": "Cotton", "categoryId": "cat", "categoryPath": ["cat"],
            "imageUrls": urls, "variantPrices": [100], "minPrice": 100, "maxPrice": 100,
            "createdAt": "2026-10-02T00:00:00Z", "updatedAt": "2026-10-02T00:00:00Z"}}
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get("X-Internal-Api-Key") != "test-internal":
                    self.send_error(401); return
                product_id = self.path.rsplit("/", 1)[-1]
                if product_id not in snapshots:
                    self.send_error(404); return
                raw = json.dumps(snapshots[product_id]).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            def log_message(self, *_): pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        settings = replace(Settings(), alias=alias, product_url=f"http://127.0.0.1:{server.server_port}", internal_key="test-internal",
            bucket=bucket, minio_key=reader, minio_secret=read_secret, llm_enabled=False)
        encoder = ImageEncoder(settings)
        encoder.load()
        client = Elasticsearch(settings.es_url, request_timeout=5, max_retries=0)
        definition = index_definition(encoder.fingerprint)
        # Synthetic calibration belongs only to this disposable test index, never the runtime alias.
        definition["mappings"]["_meta"]["calibration"] = {"fingerprint": encoder.fingerprint, "validated": True, "threshold": -1}
        client.indices.create(index=index, body=definition)
        client.indices.put_alias(index=index, name=alias, is_write_index=True)
        store = SearchStore(settings, client)
        workers = Workers(store, encoder)
        try:
            readonly = boto3.client("s3", endpoint_url=settings.minio_endpoint, aws_access_key_id=reader,
                aws_secret_access_key=read_secret, region_name="us-east-1", config=Config(proxies={}, s3={"addressing_style": "path"}))
            with self.assertRaises(ClientError): readonly.put_object(Bucket=bucket, Key="products/forbidden.png", Body=b"x")
            with self.assertRaises(ClientError): readonly.list_objects_v2(Bucket=bucket)
            readonly.close()
            with patch.object(event_consumer, "QUEUE", queue), patch.object(event_consumer, "EXCHANGE", exchange):
                # Encoder was loaded once here; start actual metadata consumer and manually poll image work.
                consumer = threading.Thread(target=workers.consume, daemon=True)
                consumer.start()
                with pika.BlockingConnection(event_consumer.connection_parameters()) as connection:
                    channel = connection.channel()
                    declare_topology(channel)
                    channel.confirm_delivery()
                    def publish(event_type):
                        channel.basic_publish(exchange=exchange, routing_key=event_type,
                            body=json.dumps({"eventId": str(uuid.uuid4()), "eventType": event_type,
                                             "timestamp": int(time.time() * 1000), "payload": {"productId": "p1"}}),
                            properties=pika.BasicProperties(delivery_mode=2), mandatory=True)
                    publish("ProductCreated")
                    wait_for(lambda: client.exists(index=index, id="p1"))
                    client.indices.refresh(index=index)
                    restarted_store = SearchStore(settings, client)
                    restarted_store.process_image_job(encoder)
                    client.indices.refresh(index=index)
                    self.assertEqual(len(client.get(index=index, id="p1")["_source"]["images"]), 9)
                    app = create_app(settings, store, encoder, run_workers=False)
                    with TestClient(app) as api:
                        self.assertEqual(api.get("/api/ai/search?keyword=ao%20phong").json()["results"][0]["id"], "p1")
                        response = api.post("/api/ai/search/image", files={"file": ("query.png", data.getvalue(), "image/png")})
                        self.assertEqual(response.status_code, 200)
                        self.assertEqual(response.json()["results"][0]["id"], "p1")
                        # Valid exhausted deliveries replay to Search only, retaining type validation.
                        channel.basic_publish(exchange="", routing_key=queue + ".dead",
                            body=json.dumps({"eventId": str(uuid.uuid4()), "eventType": "ProductUpdated",
                                "timestamp": int(time.time() * 1000), "payload": {"productId": "p1"}}),
                            properties=pika.BasicProperties(delivery_mode=2), mandatory=True)
                        snapshots["p1"].update(productVersion=1, name="Giày sneaker")
                        with patch.object(manage, "QUEUE", queue):
                            manage.replay_dead(1)
                        wait_for(lambda: client.get(index=index, id="p1")["_source"]["productVersion"] == 1)
                        self.assertEqual(channel.queue_declare(queue=queue + ".dead", passive=True).method.message_count, 0)
                        publish("ProductUpdated")
                        wait_for(lambda: client.get(index=index, id="p1")["_source"]["productVersion"] == 1)
                        if os.getenv("SEARCH_LOAD_SECONDS"):
                            duration = int(os.environ["SEARCH_LOAD_SECONDS"])
                            self.assertTrue(1 <= duration <= 60)
                            end = time.monotonic() + duration
                            def traffic(user):
                                samples = []
                                step = user * 4
                                while time.monotonic() < end:
                                    kind = "keyword" if step % 20 < 13 else "suggestions" if step % 20 < 18 else "image"
                                    start = time.monotonic()
                                    if kind == "keyword": response = api.get("/api/ai/search?keyword=ao%20thun")
                                    elif kind == "suggestions": response = api.get("/api/ai/search/suggestions?keyword=ao")
                                    else: response = api.post("/api/ai/search/image", files={"file": ("query.png", data.getvalue())})
                                    samples.append((kind, time.monotonic() - start, response.status_code))
                                    step += 1
                                    time.sleep(1)
                                return samples
                            with ThreadPoolExecutor(max_workers=5) as pool:
                                futures = [pool.submit(traffic, user) for user in range(5)]
                                while time.monotonic() + 5 < end:
                                    time.sleep(5)
                                    snapshots["p1"].update(productVersion=snapshots["p1"]["productVersion"] + 1)
                                    publish("ProductUpdated")
                                    revision = snapshots["p1"]["productVersion"]
                                    wait_for(lambda: client.get(index=index, id="p1")["_source"]["productVersion"] == revision)
                                samples = [sample for future in futures for sample in future.result()]
                            report = {"scope": "ASGI + real ES/MinIO/PyTorch/Rabbit; snapshot stub; LLM disabled",
                                "users": 5, "durationSeconds": duration, "thinkSeconds": 1,
                                "targetMix": {"keyword": 65, "suggestions": 25, "image": 10},
                                "metadataLagMs": workers.metadata_lag_ms, "endpoints": {}}
                            for kind, ceiling in (("keyword", 1), ("suggestions", 0.3), ("image", 3)):
                                values = sorted(s[1] for s in samples if s[0] == kind)
                                statuses = [s[2] for s in samples if s[0] == kind]
                                report["endpoints"][kind] = {"requests": len(values), "p95Seconds": values[int(0.95 * (len(values) - 1))],
                                    "maximumSeconds": max(values), "non200": sum(s != 200 for s in statuses)}
                                self.assertLessEqual(report["endpoints"][kind]["p95Seconds"], ceiling)
                                self.assertTrue(all(status in {200, 429} for status in statuses))
                            manage.write_json(ROOT / ".local/generated-load-report.json", report)
                            print(json.dumps(report))
                        snapshots["p1"].update(productVersion=snapshots["p1"]["productVersion"] + 1, catalogVisible=False)
                        revision = snapshots["p1"]["productVersion"]
                        publish("ProductUpdated")
                        wait_for(lambda: client.get(index=index, id="p1")["_source"]["productVersion"] == revision)
                        client.indices.refresh(index=index)
                        self.assertEqual(api.get("/api/ai/search?keyword=sneaker").json()["results"], [])
                        self.assertEqual(api.post("/api/ai/search/image", files={"file": ("query.png", data.getvalue())}).json()["results"], [])
                        snapshots["p1"] = {"id": "p1", "productVersion": revision + 1, "catalogVisible": False, "deleted": True, "deletedAt": "2026-10-02T00:00:00Z"}
                        publish("ProductDeleted")
                        wait_for(lambda: client.get(index=index, id="p1")["_source"]["deleted"])
                        channel.basic_publish(exchange=exchange, routing_key="ProductUpdated", body=b"invalid", properties=pika.BasicProperties(delivery_mode=2))
                        wait_for(lambda: channel.queue_declare(queue=queue + ".dead", passive=True).method.message_count == 1)
                        wait_for(lambda: channel.queue_declare(queue=queue, passive=True).method.message_count == 0)
                        workers.stop.set()
                        consumer.join(timeout=5)
                        channel.queue_delete(queue=queue)
                        channel.queue_delete(queue=queue + ".dead")
                        channel.exchange_delete(exchange=exchange)
                        channel.exchange_delete(exchange=exchange + ".dlx")
        finally:
            workers.close()
            server.shutdown()
            server.server_close()
            # TestClient closes its supplied client; delete using a fresh owned connection.
            with Elasticsearch(settings.es_url, request_timeout=5) as cleanup:
                cleanup.indices.delete(index=index)
            s3.delete_objects(Bucket=bucket, Delete={"Objects": [{"Key": u.split(f"/{bucket}/")[1]} for u in urls]})
            s3.delete_bucket(Bucket=bucket)
            s3.close()
            mc('mc admin policy detach it "$TEST_READER" --user "$TEST_READER" >/dev/null; '
               'mc admin user remove it "$TEST_READER" >/dev/null; mc admin policy remove it "$TEST_READER" >/dev/null; mc alias remove it >/dev/null', TEST_READER=reader)
            with pika.BlockingConnection(event_consumer.connection_parameters()) as cleanup_connection:
                cleanup_channel = cleanup_connection.channel()
                cleanup_channel.queue_delete(queue=queue)
                cleanup_channel.queue_delete(queue=queue + ".dead")
                cleanup_channel.exchange_delete(exchange=exchange)
                cleanup_channel.exchange_delete(exchange=exchange + ".dlx")


if __name__ == "__main__": unittest.main()
